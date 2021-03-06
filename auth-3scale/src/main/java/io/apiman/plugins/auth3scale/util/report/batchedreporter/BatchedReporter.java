/*
 * Copyright 2016 JBoss Inc
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
package io.apiman.plugins.auth3scale.util.report.batchedreporter;

import io.apiman.common.logging.IApimanLogger;
import io.apiman.gateway.engine.async.IAsyncHandler;
import io.apiman.gateway.engine.components.IHttpClientComponent;
import io.apiman.gateway.engine.components.IPeriodicComponent;
import io.apiman.gateway.engine.components.http.HttpMethod;
import io.apiman.gateway.engine.components.http.IHttpClientRequest;
import io.apiman.gateway.engine.policy.IPolicyContext;
import io.apiman.plugins.auth3scale.util.report.ReportResponseHandler;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import com.google.common.collect.EvictingQueue;

/**
 * @author Marc Savy {@literal <msavy@redhat.com>}
 */
@SuppressWarnings("nls")
public class BatchedReporter {
    // Change to RingBuffer?
    private Set<Reporter> reporters = new LinkedHashSet<>();
    private RetryReporter retryReporter;
    private IPeriodicComponent periodic;
    private IHttpClientComponent httpClient;
    private long timerId;

    private boolean started = false;
    private volatile boolean sending = false;
    private IApimanLogger logger;

    public BatchedReporter start(IPolicyContext context, BatchedReporterOptions options) {
        if (started) {
            throw new IllegalStateException("Already started");
        }

        this.retryReporter = new RetryReporter(options.getRetryQueueMaxSize());
        reporters.add(retryReporter);

        this.httpClient = context.getComponent(IHttpClientComponent.class);
        this.periodic = context.getComponent(IPeriodicComponent.class);
        this.logger = context.getLogger(BatchedReporter.class);

        this.timerId = periodic.setPeriodicTimer(options.getReportingInterval(),
                options.getInitialWait(),
                id -> send());
        started = true;
        return this;
    }

    public void stop() {
        periodic.cancelTimer(timerId);
    }

    public boolean isStarted() {
        return started;
    }

    public BatchedReporter addReporter(Reporter reporter) {
        reporter.setFullHandler(isFull -> send());
        reporters.add(reporter);
        return this;
    }

    // Avoid any double sending weirdness.
    private void send() {
        if (!sending) {
            synchronized (this) {
                if (!sending) {
                    sending = true;
                    doSend();
                }
            }
        }
    }

    private volatile int itemsOfWork = 0;

    // speed up / slow down (primitive back-pressure mechanism?)
    private void doSend() {
        for (Reporter reporter : reporters) {
            List<EncodedReport> sendItList = reporter.encode();

            for (final EncodedReport sendIt : sendItList) {
                itemsOfWork++;
                logger.debug("[Report {}] Attempting to send: {}", sendIt.hashCode(), sendIt);
                IHttpClientRequest post = httpClient.request(sendIt.getEndpoint().toString(), // TODO change to broken down components
                        HttpMethod.POST,
                        handleResponse(sendIt));
                post.addHeader("Content-Type", sendIt.getContentType());
                post.write(sendIt.getData(), "UTF-8");
                post.end();
            }
        }
        checkFinishedSending();
    }

    private ReportResponseHandler handleResponse(EncodedReport report) {
        return new ReportResponseHandler(reportResult -> {
            logger.debug("[Report {}] Send result: {}", report.hashCode(), reportResult.getResult());
            // Flush back to allow caller to invalidate cache, etc.
            if (reportResult.isSuccess()) {
                report.flush(reportResult);
            } else { // Retry on failure.
                logger.debug("[Report {}] Will retry: {}", report.hashCode(), report);
                retryReporter.addRetry(report);
            }

            itemsOfWork--;
            checkFinishedSending();
        });
    }

    private void checkFinishedSending() {
        if (itemsOfWork <= 0) {
            itemsOfWork = 0;
            sending = false;
        }
    }

    private static final class RetryReporter implements Reporter {
        private final Queue<EncodedReport> resendReports;

        public RetryReporter(int maxSize) {
            resendReports = EvictingQueue.create(maxSize);
        }

        @Override
        public List<EncodedReport> encode() {
            List<EncodedReport> copy = new LinkedList<>(resendReports);
            resendReports.clear(); // Some may end up coming back again if retry fails.
            return copy;
        }

        // Never call full; we just evict old records once limit is hit.
        public RetryReporter addRetry(EncodedReport report) {
            resendReports.offer(report);
            return this;
        }

        @Override
        public Reporter setFullHandler(IAsyncHandler<Void> fullHandler) {
            return null;
        }
    }

}
