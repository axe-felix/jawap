package org.stagemonitor.tracing.elasticsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stagemonitor.configuration.ConfigurationRegistry;
import org.stagemonitor.core.CorePlugin;
import org.stagemonitor.core.elasticsearch.ElasticsearchClient;
import org.stagemonitor.core.metrics.metrics2.Metric2Registry;
import org.stagemonitor.core.metrics.metrics2.MetricName;
import org.stagemonitor.core.util.ExecutorUtils;
import org.stagemonitor.core.util.HttpClient;
import org.stagemonitor.core.util.HttpClient.OutputStreamHandler;
import org.stagemonitor.core.util.JsonUtils;
import org.stagemonitor.tracing.B3HeaderFormat;
import org.stagemonitor.tracing.SpanContextInformation;
import org.stagemonitor.tracing.TracingPlugin;
import org.stagemonitor.tracing.reporter.SpanReporter;
import org.stagemonitor.tracing.wrapper.SpanWrapper;
import org.stagemonitor.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.stagemonitor.core.elasticsearch.ElasticsearchClient.CONTENT_TYPE_NDJSON;
import static org.stagemonitor.core.metrics.metrics2.MetricName.name;
import static org.stagemonitor.tracing.B3IdentifierTagger.PARENT_ID;
import static org.stagemonitor.tracing.B3IdentifierTagger.SPAN_ID;
import static org.stagemonitor.tracing.B3IdentifierTagger.TRACE_ID;

/**
 * Holds spans in a {@link BlockingQueue} and flushes them asynchronously via a bulk request to Elasticsearch based on three conditions:
 * <ul>
 *     <li>Periodically, after the flush interval (defaults to 1 sec)</li>
 *     <li>When the span queue exceeds the max batch size and there is currently no flush scheduled, a immediate async flush is scheduled</li>
 *     <li>If the span queue size is still higher than the max batch size after a flush, spans are flushed again</li>
 * </ul>
 * If the queue is full, spans are dropped to prevent excessive heap usage and {@link OutOfMemoryError}s
 */
public class ElasticsearchSpanReporter extends SpanReporter {

	static final MetricName spansDroppedMetricName = name("elasticsearch_spans_dropped").build();
	static final MetricName bulkSizeMetricName = name("elasticsearch_spans_bulk_size").build();
	static final String ES_SPAN_LOGGER = "ElasticsearchSpanReporter";
	static final Charset UTF_8 = Charset.forName("UTF-8");
	private static final byte[] indexHeader = "{\"index\":{}}\n".getBytes(UTF_8);
	private static final String SPANS_TYPE = "spans";
	private static final Logger logger = LoggerFactory.getLogger(ElasticsearchSpanReporter.class);

	private final Logger spanLogger;
	private SpanFlushingRunnable spanFlushingRunnable;

	private ElasticsearchTracingPlugin elasticsearchTracingPlugin;
	private ElasticsearchClient elasticsearchClient;
	private BlockingQueue<OutputStreamHandler> bulkQueue;
	private TracingPlugin tracingPlugin;
	private Metric2Registry metricRegistry;
	private ScheduledThreadPoolExecutor scheduler;
	private ElasticsearchUpdateSpanReporter updateReporter;

	public ElasticsearchSpanReporter() {
		this(LoggerFactory.getLogger(ES_SPAN_LOGGER));
	}

	ElasticsearchSpanReporter(Logger spanLogger) {
		this.spanLogger = spanLogger;
	}

	@Override
	public void init(ConfigurationRegistry configuration) {
		final CorePlugin corePlugin = configuration.getConfig(CorePlugin.class);
		tracingPlugin = configuration.getConfig(TracingPlugin.class);
		elasticsearchTracingPlugin = configuration.getConfig(ElasticsearchTracingPlugin.class);
		elasticsearchClient = corePlugin.getElasticsearchClient();
		metricRegistry = corePlugin.getMetricRegistry();
		scheduler = ExecutorUtils.createSingleThreadSchedulingDeamonPool("elasticsearch-reporter", 10, corePlugin);
		spanFlushingRunnable = new SpanFlushingRunnable(new FlushCallable());
		scheduler.scheduleWithFixedDelay(spanFlushingRunnable, elasticsearchTracingPlugin.getFlushDelayMs(),
				elasticsearchTracingPlugin.getFlushDelayMs(), TimeUnit.MILLISECONDS);
		bulkQueue = new ArrayBlockingQueue<OutputStreamHandler>(elasticsearchTracingPlugin.getMaxQueueSize());
		this.updateReporter = new ElasticsearchUpdateSpanReporter(corePlugin, tracingPlugin, elasticsearchTracingPlugin, this);
	}

	@Override
	public void report(SpanContextInformation spanContext, final SpanWrapper spanWrapper) {
		logger.debug("Reporting span");
		if (elasticsearchTracingPlugin.isOnlyLogElasticsearchSpanReports()) {
			final String spansIndex = "stagemonitor-spans-" + StringUtils.getLogstashStyleDate();
			spanLogger.info(ElasticsearchClient.getBulkHeader("index", spansIndex, SPANS_TYPE) + JsonUtils.toJson(spanWrapper));
		} else {
			scheduleSendBulk(new SpanBulkIndexOutputStreamHandler(spanWrapper));
		}
	}

	void scheduleSendBulk(OutputStreamHandler bulkBytes) {
		if (logger.isDebugEnabled()) {
			logger.debug("Scheduling bulk request\n{}", bulkBytes.toString());
		}
		final boolean addedToQueue = bulkQueue.offer(bulkBytes);
		if (!addedToQueue) {
			metricRegistry.counter(spansDroppedMetricName).inc();
		}
		scheduleFlushIfBulkQueueExceedsMaxBatchSize();
		if (!tracingPlugin.isReportAsync()) {
			spanFlushingRunnable.run();
		}
	}

	private void scheduleFlushIfBulkQueueExceedsMaxBatchSize() {
		if (bulkQueue.size() > elasticsearchTracingPlugin.getMaxBatchSize() && scheduler.getQueue().isEmpty()) {
			synchronized (this) {
				if (scheduler.getQueue().isEmpty()) {
					scheduler.schedule(spanFlushingRunnable, 0, TimeUnit.SECONDS);
				}
			}
		}
	}

	private static class SpanBulkIndexOutputStreamHandler implements OutputStreamHandler {

		private final SpanWrapper spanWrapper;

		private SpanBulkIndexOutputStreamHandler(SpanWrapper spanWrapper) {
			this.spanWrapper = spanWrapper;
		}

		@Override
		public void withHttpURLConnection(OutputStream os) throws IOException {
			os.write(indexHeader);
			JsonUtils.writeWithoutClosingStream(os, spanWrapper);
			os.write('\n');
		}
	}

	private class FlushCallable implements Callable<Boolean> {
		private final List<OutputStreamHandler> currentBulk = new ArrayList<OutputStreamHandler>(elasticsearchTracingPlugin.getMaxBatchSize());
		private final BulkErrorCountingResponseHandler responseHandler = new BulkErrorCountingResponseHandler(metricRegistry);
		private final BulkWritingOutputStreamHandler outputStreamHandler = new BulkWritingOutputStreamHandler();
		private HttpClient httpClient = elasticsearchClient.getHttpClient();

		@Override
		public Boolean call() throws Exception {
			final int maxBatchSize = elasticsearchTracingPlugin.getMaxBatchSize();
			// the batching with drainTo should remove most of the contention imposed by the queue
			// as there is less contention on the head of the queue caused by elements being added and immediately removed
			bulkQueue.drainTo(currentBulk, maxBatchSize);
			if (currentBulk.isEmpty()) {
				return false;
			}
			logger.debug("Flushing {} span batch requests", currentBulk.size());
			metricRegistry.histogram(bulkSizeMetricName).update(currentBulk.size());
			sendBulkRequest();
			// reusing the batch list is safe as this method is executed single threaded
			currentBulk.clear();
			return bulkQueue.size() >= maxBatchSize;
		}

		private void sendBulkRequest() {
			if (!elasticsearchClient.isElasticsearchAvailable()) {
				return;
			}
			final String url = elasticsearchClient.getElasticsearchUrl() + "/stagemonitor-spans-" + StringUtils.getLogstashStyleDate() + "/" + SPANS_TYPE + "/_bulk";
			httpClient.send("POST", url, CONTENT_TYPE_NDJSON, outputStreamHandler, responseHandler);
		}

		private class BulkWritingOutputStreamHandler implements OutputStreamHandler {
			@Override
			public void withHttpURLConnection(OutputStream os) throws IOException {
				for (OutputStreamHandler bulkLine : currentBulk) {
					bulkLine.withHttpURLConnection(os);
				}
				os.close();
			}
		}

		private class BulkErrorCountingResponseHandler extends ElasticsearchClient.BulkErrorCountingResponseHandler {
			private final Metric2Registry metricRegistry;

			private BulkErrorCountingResponseHandler(Metric2Registry metricRegistry) {
				this.metricRegistry = metricRegistry;
			}

			@Override
			public void onBulkError(int errorCount) {
				metricRegistry.counter(spansDroppedMetricName).inc(errorCount);
			}
		}
	}

	@Override
	public boolean isActive(SpanContextInformation spanContext) {
		final boolean logOnly = elasticsearchTracingPlugin.isOnlyLogElasticsearchSpanReports();
		return elasticsearchClient.isElasticsearchAvailable() || logOnly;
	}

	@Override
	public void updateSpan(B3HeaderFormat.B3Identifiers spanIdentifiers, B3HeaderFormat.B3Identifiers newSpanIdentifiers, Map<String, Object> tagsToUpdate) {
		int tagsSize = tagsToUpdate != null ? tagsToUpdate.size() : 0;
		final HashMap<String, Object> tags = new HashMap<String, Object>(tagsSize + 3);
		if (tagsToUpdate != null) {
			tags.putAll(tagsToUpdate);
		}
		if (newSpanIdentifiers != null) {
			tags.put(SPAN_ID, newSpanIdentifiers.getSpanId());
			tags.put(TRACE_ID, newSpanIdentifiers.getTraceId());
			if (newSpanIdentifiers.getParentSpanId() != null) {
				tags.put(PARENT_ID, newSpanIdentifiers.getParentSpanId());
			}
		}
		updateReporter.updateSpan(spanIdentifiers, tags);
	}

	ElasticsearchUpdateSpanReporter getUpdateReporter() {
		return updateReporter;
	}

}
