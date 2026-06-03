package org.xmlpipeline.service;

import org.xmlpipeline.exception.FetchException;
import org.xmlpipeline.exception.RetryableFetchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * HTTP fetcher with manual exponential-backoff retry.
 *
 * Retry policy
 * ────────────
 * Retryable    : network errors (IOException, timeout), HTTP 5xx
 * Non-retryable: HTTP 404, other HTTP 4xx client errors
 * Max attempts : 3   Back-off: 1 s → 2 s → 4 s
 *
 * Uses java.net.http.HttpClient (built-in since Java 11).
 * Called from a thread-pool thread so blocking .send() is perfectly fine.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FetcherService {

    private final HttpClient httpClient;

    @Value("${app.max-retries:3}")
    private int maxRetries;

    @Value("${app.request-timeout-seconds:30}")
    private int requestTimeoutSeconds;

    private static final long[] BACKOFF_MILLIS = {1_000, 2_000, 4_000};

    /**
     * Fetch the given URL and return its body as raw bytes.
     *
     * @throws FetchException          non-retryable (404, 4xx)
     * @throws RetryableFetchException all retry attempts exhausted (network error, 5xx)
     */
    public byte[] fetch(String url, String jobId) {
        Exception lastException = null;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            log.info("fetch_started",
                    kv("job_id", jobId),
                    kv("url", url),
                    kv("attempt", attempt + 1),
                    kv("max_attempts", maxRetries));

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                        .header("User-Agent", "XMLPipelineBot/1.0")
                        .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();

                if (status == 404) {
                    log.warn("fetch_404", kv("job_id", jobId), kv("url", url), kv("status", status));
                    throw new FetchException("404 Not Found: " + url);
                }

                if (status >= 400 && status < 500) {
                    log.warn("fetch_client_error",
                            kv("job_id", jobId), kv("url", url), kv("status", status));
                    throw new FetchException("HTTP " + status + " client error: " + url);
                }

                if (status >= 500) {
                    // Server error — retryable
                    throw new IOException("HTTP " + status + " server error");
                }

                log.info("fetch_success",
                        kv("job_id", jobId),
                        kv("url", url),
                        kv("attempt", attempt + 1),
                        kv("bytes", response.body().length));

                return response.body();

            } catch (FetchException e) {
                throw e; // never retry 4xx errors

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RetryableFetchException("Interrupted during fetch: " + url, e);

            } catch (Exception e) {
                lastException = e;
                long waitMs = attempt < BACKOFF_MILLIS.length
                        ? BACKOFF_MILLIS[attempt]
                        : BACKOFF_MILLIS[BACKOFF_MILLIS.length - 1];

                log.warn("fetch_retry",
                        kv("job_id", jobId),
                        kv("url", url),
                        kv("attempt", attempt + 1),
                        kv("wait_ms", waitMs),
                        kv("error", e.getMessage()));

                if (attempt < maxRetries - 1) {
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RetryableFetchException("Interrupted during backoff sleep", ie);
                    }
                }
            }
        }

        log.error("fetch_failed",
                kv("job_id", jobId),
                kv("url", url),
                kv("attempts", maxRetries),
                kv("error", lastException != null ? lastException.getMessage() : "unknown"));

        throw new RetryableFetchException(
                "Failed after " + maxRetries + " attempts: "
                        + (lastException != null ? lastException.getMessage() : "unknown"),
                lastException);
    }
}
