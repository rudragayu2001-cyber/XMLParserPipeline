package org.xmlpipeline.service;

import org.xmlpipeline.entity.FeedRecord;
import org.xmlpipeline.entity.Job;
import org.xmlpipeline.entity.Task;
import org.xmlpipeline.repository.FeedRecordRepository;
import org.xmlpipeline.repository.JobRepository;
import org.xmlpipeline.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * All database write operations for job/task lifecycle live here.
 *
 * WHY a separate class?
 * ---------------------
 * Spring @Transactional works via AOP proxy. If ProcessorService called these
 * methods on itself (this.markTaskInProgress()), the proxy would be bypassed and
 * @Transactional would have no effect — causing TransactionRequiredException on
 * every @Modifying UPDATE query.
 *
 * By extracting into this bean, ProcessorService calls through the Spring proxy,
 * so each method correctly gets its own transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskPersistenceService {

    private final JobRepository        jobRepository;
    private final TaskRepository       taskRepository;
    private final FeedRecordRepository recordRepository;

    @Transactional
    public List<UUID> initJob(UUID jobId, List<String> urls) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        job.setStatus("running");
        job.setStartedAt(LocalDateTime.now());
        jobRepository.save(job);

        List<UUID> ids = new ArrayList<>(urls.size());
        for (String url : urls) {
            Task task = new Task();
            task.setJob(job);
            task.setUrl(url);
            task.setStatus("pending");
            taskRepository.save(task);
            ids.add(task.getId());
        }
        return ids;
    }

    @Transactional
    public void markTaskInProgress(UUID taskId) {
        taskRepository.markInProgress(taskId, "in_progress", LocalDateTime.now());
    }

    @Transactional
    public int persistRecords(List<ParserService.ParsedEntry> entries, UUID taskId, UUID jobId) {
        if (entries.isEmpty()) return 0;

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        List<FeedRecord> records = new ArrayList<>(entries.size());
        for (ParserService.ParsedEntry e : entries) {
            FeedRecord r = new FeedRecord();
            r.setTask(task);
            r.setJobId(jobId);
            r.setTitle(emptyToNull(e.title()));
            r.setLink(emptyToNull(e.link()));
            r.setPublishedDate(e.publishedDate());
            r.setAuthor(emptyToNull(e.author()));
            r.setSummary(emptyToNull(e.summary()));
            records.add(r);
        }
        recordRepository.saveAll(records);
        return records.size();
    }

    @Transactional
    public void markTaskCompleted(UUID taskId, int count) {
        taskRepository.markCompleted(taskId, "completed", count, LocalDateTime.now());
    }

    @Transactional
    public void markTaskFailed(UUID taskId, String error) {
        String msg = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        taskRepository.markFailed(taskId, msg, LocalDateTime.now());
    }

    @Transactional
    public void finaliseJob(UUID jobId) {
        long failed    = taskRepository.countByJobIdAndStatus(jobId, "failed");
        long completed = taskRepository.countByJobIdAndStatus(jobId, "completed");
        String finalStatus = failed == 0 ? "completed" : "completed_with_errors";

        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(finalStatus);
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
        });

        log.info("job_completed",
                kv("job_id", jobId),
                kv("status", finalStatus),
                kv("completed", completed),
                kv("failed", failed));
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
