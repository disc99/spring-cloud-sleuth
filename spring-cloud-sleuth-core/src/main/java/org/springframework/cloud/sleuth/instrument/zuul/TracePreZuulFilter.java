/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.zuul;

import java.util.Map;

import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.SpanAccessor;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;

/**
 * A pre request {@link ZuulFilter} that sets tracing related headers on the request
 * from the current span. We're doing so to ensure tracing propagates to the next hop.
 *
 * @author Dave Syer
 *
 * @since 1.0.0
 */
public class TracePreZuulFilter extends ZuulFilter {

	private final SpanAccessor accessor;

	public TracePreZuulFilter(SpanAccessor accessor) {
		this.accessor = accessor;
	}

	@Override
	public boolean shouldFilter() {
		return true;
	}

	@Override
	public Object run() {
		RequestContext ctx = RequestContext.getCurrentContext();
		Map<String, String> requestHeaders = ctx.getZuulRequestHeaders();
		Span span = getCurrentSpan();
		if (span == null) {
			setHeader(requestHeaders, Span.NOT_SAMPLED_NAME, "true");
			return null;
		}
		try {
			setHeader(requestHeaders, Span.SPAN_ID_NAME, span.getSpanId());
			setHeader(requestHeaders, Span.TRACE_ID_NAME, span.getTraceId());
			setHeader(requestHeaders, Span.SPAN_NAME_NAME, span.getName());
			if (!span.isExportable()) {
				setHeader(requestHeaders, Span.NOT_SAMPLED_NAME, "true");
			}
			setHeader(requestHeaders, Span.PARENT_ID_NAME, getParentId(span));
			setHeader(requestHeaders, Span.PROCESS_ID_NAME, span.getProcessId());
			// TODO: the client sent event should come from the client not the filter!
			span.logEvent(Span.CLIENT_SEND);
		}
		catch (Exception ex) {
			ReflectionUtils.rethrowRuntimeException(ex);
		}
		return null;
	}

	private Span getCurrentSpan() {
		return this.accessor.getCurrentSpan();
	}

	private Long getParentId(Span span) {
		return !span.getParents().isEmpty() ? span.getParents().get(0) : null;
	}

	public void setHeader(Map<String, String> request, String name, String value) {
		if (StringUtils.hasText(value) && !request.containsKey(name) && this.accessor.isTracing()) {
			request.put(name, value);
		}
	}

	public void setHeader(Map<String, String> request, String name, Long value) {
		if (value != null) {
			setHeader(request, name, Span.idToHex(value));
		}
	}

	@Override
	public String filterType() {
		return "pre";
	}

	@Override
	public int filterOrder() {
		return 0;
	}

}