/*
 * Copyright 2013-2015 the original author or authors.
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

import java.io.InputStream;
import java.net.URISyntaxException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.netflix.ribbon.SpringClientFactory;
import org.springframework.cloud.netflix.zuul.filters.route.RestClientRibbonCommand;
import org.springframework.cloud.netflix.zuul.filters.route.RestClientRibbonCommandFactory;
import org.springframework.cloud.netflix.zuul.filters.route.RibbonCommandContext;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.SpanAccessor;
import org.springframework.util.MultiValueMap;

import com.netflix.client.http.HttpRequest;
import com.netflix.niws.client.http.RestClient;

/**
 * Propagates traces downstream via http headers that contain trace metadata.
 *
 * @author Spencer Gibb
 *
 * @since 1.0.0
 */
public class TraceRestClientRibbonCommandFactory extends RestClientRibbonCommandFactory {

	private static final Log log = LogFactory.getLog(TraceRestClientRibbonCommandFactory.class);

	private final SpanAccessor accessor;

	public TraceRestClientRibbonCommandFactory(SpringClientFactory clientFactory,
			SpanAccessor accessor) {
		super(clientFactory);
		this.accessor = accessor;
	}

	@Override
	@SuppressWarnings("deprecation")
	public RestClientRibbonCommand create(RibbonCommandContext context) {
		RestClient restClient = getClientFactory().getClient(context.getServiceId(),
				RestClient.class);
		try {
			return new TraceRestClientRibbonCommand(context.getServiceId(), restClient,
					getVerb(context.getVerb()), context.getUri(), context.getRetryable(),
					context.getHeaders(), context.getParams(), context.getRequestEntity(),
					this.accessor);
		}
		catch (URISyntaxException e) {
			log.error("Exception occurred while trying to create the TraceRestClientRibbonCommand", e);
			throw new RuntimeException(e);
		}
	}

	class TraceRestClientRibbonCommand extends RestClientRibbonCommand {

		private final SpanAccessor accessor;

		@SuppressWarnings("deprecation")
		public TraceRestClientRibbonCommand(String commandKey, RestClient restClient,
				HttpRequest.Verb verb, String uri, Boolean retryable,
				MultiValueMap<String, String> headers,
				MultiValueMap<String, String> params, InputStream requestEntity,
				SpanAccessor accessor)
						throws URISyntaxException {
			super(commandKey, restClient, verb, uri, retryable, headers, params,
					requestEntity);
			this.accessor = accessor;
		}

		@Override
		protected void customizeRequest(HttpRequest.Builder requestBuilder) {
			Span span = getCurrentSpan();
			if (span == null) {
				setHeader(requestBuilder, Span.NOT_SAMPLED_NAME, "true");
				return;
			}
			setHeader(requestBuilder, Span.TRACE_ID_NAME, Span.idToHex(span.getTraceId()));
			setHeader(requestBuilder, Span.SPAN_ID_NAME, Span.idToHex(span.getSpanId()));
			setHeader(requestBuilder, Span.SPAN_NAME_NAME, span.getName());
			if (getParentId(span) != null) {
				setHeader(requestBuilder, Span.PARENT_ID_NAME,
						Span.idToHex(getParentId(span)));
			}
			setHeader(requestBuilder, Span.PROCESS_ID_NAME,
					span.getProcessId());
			span.logEvent(Span.CLIENT_SEND);
		}

		private Long getParentId(Span span) {
			return !span.getParents().isEmpty()
					? span.getParents().get(0) : null;
		}

		public void setHeader(HttpRequest.Builder builder, String name, String value) {
			if (value != null && this.accessor.isTracing()) {
				builder.header(name, value);
			}
		}

		private Span getCurrentSpan() {
			return this.accessor.getCurrentSpan();
		}

	}
}