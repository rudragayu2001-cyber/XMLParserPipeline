package org.xmlpipeline.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application-level beans.
 *
 * Concurrency model: Cached thread pool + Semaphore (Java 17 compatible)
 * -----------------------------------------------------------------------
 * Each URL task is submitted to a CachedThreadPool, giving one platform thread
 * per in-flight task. A Semaphore(MAX_CONCURRENT) caps simultaneous HTTP requests
 * so we never exceed the configured concurrency limit — threads beyond the semaphore
 * block cheaply waiting to acquire a permit.
 *
 * Note: On Java 21, these can be swapped to Executors.newVirtualThreadPerTaskExecutor()
 * for even lower overhead, but the platform-thread model works correctly here.
 */
@Configuration
public class AppConfig {

    @Value("${app.request-timeout-seconds:30}")
    private int requestTimeoutSeconds;

    /**
     * Shared HttpClient for all URL fetches.
     * HttpClient is thread-safe and reuses connections across requests.
     */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Executor for per-URL task threads inside a job.
     * CachedThreadPool: reuses idle threads; pool size is bounded by the Semaphore
     * in ProcessorService, not by this executor directly.
     * destroyMethod="shutdown" ensures graceful drain on application stop.
     */
    @Bean(name = "jobExecutor", destroyMethod = "shutdown")
    public ExecutorService jobExecutor() {
        return Executors.newCachedThreadPool(namedThreadFactory("job-worker"));
    }

    /**
     * Spring @Async executor — used for fire-and-forget job dispatch from JobService.
     * A single daemon thread is enough; it just kicks off the job and returns.
     */
    @Bean(name = "asyncExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-job-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
