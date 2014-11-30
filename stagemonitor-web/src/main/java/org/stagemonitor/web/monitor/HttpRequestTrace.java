package org.stagemonitor.web.monitor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import net.sf.uadetector.ReadableUserAgent;
import net.sf.uadetector.UserAgentStringParser;
import net.sf.uadetector.service.UADetectorServiceFactory;
import org.stagemonitor.core.Stagemonitor;
import org.stagemonitor.requestmonitor.RequestTrace;
import org.stagemonitor.web.WebPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This class extends the generic request trace with data specific for http requests
 */
public class HttpRequestTrace extends RequestTrace {

	private final UserAgentStringParser parser = UADetectorServiceFactory.getResourceModuleParser();
	private static final int MAX_ELEMENTS = 100;
	private static final Map<String, ReadableUserAgent> userAgentCache =
			Collections.synchronizedMap(new LinkedHashMap<String, ReadableUserAgent>(MAX_ELEMENTS + 1, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry eldest) {
					return size() > MAX_ELEMENTS;
				}
			});

	private final String url;
	private Integer statusCode;
	private final Map<String, String> headers;
	private final String method;
	private Integer bytesWritten;
	private UserAgentInformation userAgent;
	private final String sessionId;
	@JsonIgnore
	private final String connectionId;

	public HttpRequestTrace(String requestId, GetNameCallback getNameCallback, String url, Map<String, String> headers, String method,
							String sessionId, String connectionId) {
		super(requestId, getNameCallback);
		this.url = url;
		this.headers = headers;
		this.sessionId = sessionId;
		this.connectionId = connectionId;
		this.method = method;
	}

	public static class UserAgentInformation {
		private final String type;
		private final String device;
		private final String os;
		private final String osFamily;
		private final String osVersion;
		private final String browser;
		private final String browserVersion;

		public UserAgentInformation(ReadableUserAgent userAgent) {
			type = userAgent.getTypeName();
			device = userAgent.getDeviceCategory().getName();
			os = userAgent.getOperatingSystem().getName();
			osFamily = userAgent.getOperatingSystem().getFamilyName();
			osVersion = userAgent.getOperatingSystem().getVersionNumber().toVersionString();
			browser = userAgent.getName();
			browserVersion = userAgent.getVersionNumber().toVersionString();
		}

		public String getType() {
			return type;
		}

		public String getDevice() {
			return device;
		}

		public String getOs() {
			return os;
		}

		public String getOsFamily() {
			return osFamily;
		}

		public String getOsVersion() {
			return osVersion;
		}

		public String getBrowser() {
			return browser;
		}

		public String getBrowserVersion() {
			return browserVersion;
		}
	}

	public String getUrl() {
		return url;
	}

	public Integer getStatusCode() {
		return statusCode;
	}

	public void setStatusCode(Integer statusCode) {
		this.statusCode = statusCode;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public String getMethod() {
		return method;
	}

	public void setBytesWritten(Integer bytesWritten) {
		this.bytesWritten = bytesWritten;
	}

	public Integer getBytesWritten() {
		return bytesWritten;
	}

	public UserAgentInformation getUserAgent() {
		if (userAgent == null && headers != null && Stagemonitor.getConfiguration(WebPlugin.class).isParseUserAgent()) {
			final String userAgentHeader = headers.get("user-agent");
			if (userAgentHeader != null) {
				ReadableUserAgent readableUserAgent = userAgentCache.get(userAgentHeader);
				if (readableUserAgent == null) {
					readableUserAgent = parser.parse(userAgentHeader);
					userAgentCache.put(userAgentHeader, readableUserAgent);
				}
				userAgent = new UserAgentInformation(readableUserAgent);
			}
		}
		return userAgent;
	}

	/**
	 * @return the http session id, <code>null</code> if there is no session associated with the request
	 */
	public String getSessionId() {
		return sessionId;
	}

	/**
	 * The connection id is used to associate ajax requests with a particular browser window in which the
	 * stagemonitor widget is running.
	 * <p/>
	 * It is used to to push request traces of ajax requests to the in browser widget.
	 *
	 * @return the connection id
	 */
	public String getConnectionId() {
		return connectionId;
	}

	@Override
	public String toString() {
		return toString(false);
	}

	public String toString(boolean asciiArt, boolean callStack) {
		StringBuilder sb = new StringBuilder(3000);
		sb.append(method).append(' ').append(url);
		if (getParameter() != null) {
			sb.append(getParameter());
		}
		sb.append(" (").append(statusCode).append(")\n");
		sb.append("id:     ").append(getId()).append('\n');
		sb.append("name:   ").append(getName()).append('\n');
		if (headers != null) {
			for (Map.Entry<String, String> entry : headers.entrySet()) {
				sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
			}
		}
		if (callStack) {
			appendCallStack(sb, asciiArt);
		}
		return sb.toString();
	}
}
