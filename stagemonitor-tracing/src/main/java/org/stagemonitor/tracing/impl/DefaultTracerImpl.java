package org.stagemonitor.tracing.impl;

import io.opentracing.*;
import io.opentracing.noop.NoopTracerFactory;
import io.opentracing.propagation.Format;
import io.opentracing.util.ThreadLocalScopeManager;

class DefaultTracerImpl implements Tracer {

	private final ScopeManager scopeManager = new ThreadLocalScopeManager();
	private final Tracer tracer;

	DefaultTracerImpl() {
		tracer = NoopTracerFactory.create();
	}

	@Override
	public ScopeManager scopeManager() {
		return scopeManager;
	}

	@Override
	public Span activeSpan() {
		return scopeManager.activeSpan();
	}

	@Override
	public SpanBuilder buildSpan(String operationName) {
		return tracer.buildSpan(operationName);
	}

	@Override
	public <C> void inject(SpanContext spanContext, Format<C> format, C carrier) {
		tracer.inject(spanContext, format, carrier);
	}

	@Override
	public <C> SpanContext extract(Format<C> format, C carrier) {
		return tracer.extract(format, carrier);
	}

	@Override
	public Scope activateSpan(Span span) {
		return scopeManager.activate(span);
	}

	@Override
	public void close() {
		tracer.close();
	}
}
