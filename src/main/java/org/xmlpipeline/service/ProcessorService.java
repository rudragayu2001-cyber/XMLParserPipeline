package org.xmlpipeline.service;

import org.xmlpipeline.exception.FetchException;
import org.xmlpipeline.exception.ParseException;
import org.xmlpipeline.exception.RetryableFetchException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Orchestrates concurrent URL processing using a cached thread pool + Semaphore.
 *
 * Concurrency model
 * ─────────────────
 * @Async fires the whole job in a background thread (Spring asyncExecutor).
 * Inside processJob(), one Runnable per URL is submitted to the shared jobExecutor
 * (CachedThreadPool). A Semaphore(MAX_CONCURRENT) caps simultaneous HTTP connections.
 * A CountDownLatch waits for all tasks to finish before finalising the job.
 *
 * All DB writes are delegated to TaskPersistenceService (a separate Spring bean)
 * so that @Transactional works correctly via Spring's AOP proxy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessorService {

    private final FetcherService          fetcherService;
    private final ParserService           parserService;
    private final TaskPersistenceService  taskPersistenceService;
    private final ExecutorService         jobExecutor;

    @Value("${app.max-concurrent:20}")
    private int maxConcurrent;

    // -------------------------------------------------------------------------
    // Job entry-point  (called via @Async from JobService)
    // -------------------------------------------------------------------------

    @Async("asyncExecutor")
    public void processJob(UUID jobId, List<String> urls) {
        log.info("job_started", kv("job_id", jobId), kv("total_urls", urls.size()));

        // 1. Mark job as running, create one Task row per URL
        List<UUID> taskIds = taskPersistenceService.initJob(jobId, urls);

        // 2. Submit all URL tasks concurrently; semaphore caps HTTP concurrency
        Semaphore semaphore = new Semaphore(maxConcurrent);
        CountDownLatch latch = new CountDownLatch(urls.size());

        for (int i = 0; i < urls.size(); i++) {
            final String url    = urls.get(i);
            final UUID   taskId = taskIds.get(i);
            jobExecutor.submit(() -> {
                try {
                    processTask(jobId, taskId, url, semaphore);
                } finally {
                    latch.countDown(); // always decrement, even on unexpected exception
                }
            });
        }

        // 3. Wait for every task to finish
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("job_interrupted", kv("job_id", jobId));
        }

        // 4. Write final job status to DB
        taskPersistenceService.finaliseJob(jobId);
    }

    // -------------------------------------------------------------------------
    // Per-URL task logic
    // -------------------------------------------------------------------------

    private void processTask(UUID jobId, UUID taskId, String url, Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            taskPersistenceService.markTaskFailed(taskId, "Interrupted while waiting for semaphore");
            return;
        }

        try {
            // Mark in_progress via proxy → @Transactional works ✅
            taskPersistenceService.markTaskInProgress(taskId);

            // Step 1 – Fetch
            byte[] content = fetcherService.fetch(url, jobId.toString());

            // Step 2 – Parse
            List<ParserService.ParsedEntry> entries = parserService.parse(content, url, jobId.toString());

            // Step 3 – Persist records
            int count = taskPersistenceService.persistRecords(entries, taskId, jobId);

            taskPersistenceService.markTaskCompleted(taskId, count);

            log.info("records_persisted",
                    kv("job_id", jobId), kv("url", url), kv("count", count));

        } catch (FetchException | ParseException e) {
            log.warn("task_failed_permanent",
                    kv("job_id", jobId), kv("url", url), kv("error", e.getMessage()));
            taskPersistenceService.markTaskFailed(taskId, e.getMessage());

        } catch (RetryableFetchException e) {
            log.warn("task_failed_retries_exhausted",
                    kv("job_id", jobId), kv("url", url), kv("error", e.getMessage()));
            taskPersistenceService.markTaskFailed(taskId, e.getMessage());

        } catch (Exception e) {
            log.error("task_failed_unexpected",
                    kv("job_id", jobId), kv("url", url), kv("error", e.getMessage()), e);
            taskPersistenceService.markTaskFailed(taskId, "Unexpected error: " + e.getMessage());

        } finally {
            semaphore.release();
        }
    }
}
