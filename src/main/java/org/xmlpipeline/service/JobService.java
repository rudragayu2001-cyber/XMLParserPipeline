package org.xmlpipeline.service;

import org.xmlpipeline.dto.*;
import org.xmlpipeline.entity.Job;
import org.xmlpipeline.entity.Task;
import org.xmlpipeline.repository.JobRepository;
import org.xmlpipeline.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobService {

    private final JobRepository   jobRepository;
    private final TaskRepository  taskRepository;
    private final ProcessorService processorService;

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Transactional
    public JobResponse createJob(List<String> urls) {
        Job job = new Job();
        job.setTotalUrls(urls.size());
        job.setStatus("pending");
        jobRepository.save(job);

        log.info("job_created", kv("job_id", job.getId()), kv("total_urls", urls.size()));

        // Dispatch AFTER this transaction commits so the async thread can find the job in DB.
        // Without this, the background thread calls initJob() before the INSERT is visible
        // (PostgreSQL READ COMMITTED), causing a silent "Job not found" failure.
        final UUID jobId = job.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                processorService.processJob(jobId, urls);
            }
        });

        return new JobResponse(job.getId().toString(), job.getStatus(), job.getTotalUrls());
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<JobResponse> listJobs(int limit) {
        return jobRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
                .stream()
                .map(j -> new JobResponse(
                        j.getId().toString(),
                        j.getStatus(),
                        j.getTotalUrls(),
                        j.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public JobStatusResponse getJobStatus(UUID jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Job not found: " + jobId));

        // Compute counts dynamically — avoids stale atomic-counter bugs
        Map<String, Long> counts = buildCountMap(jobId);

        Double elapsed = null;
        if (job.getStartedAt() != null) {
            LocalDateTime end = job.getCompletedAt() != null ? job.getCompletedAt() : LocalDateTime.now();
            elapsed = (double) Duration.between(job.getStartedAt(), end).toMillis() / 1000.0;
        }

        return JobStatusResponse.builder()
                .jobId(job.getId().toString())
                .status(job.getStatus())
                .totalUrls(job.getTotalUrls())
                .completed(counts.getOrDefault("completed", 0L))
                .failed(counts.getOrDefault("failed", 0L))
                .inProgress(counts.getOrDefault("in_progress", 0L))
                .pending(counts.getOrDefault("pending", 0L))
                .elapsedSeconds(elapsed)
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public List<TaskStatusResponse> getJobTasks(UUID jobId, String statusFilter) {
        // Validate job exists
        jobRepository.findById(jobId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Job not found: " + jobId));

        List<Task> tasks = statusFilter != null
                ? taskRepository.findByJobIdAndStatus(jobId, statusFilter)
                : taskRepository.findByJobId(jobId);

        return tasks.stream()
                .map(t -> TaskStatusResponse.builder()
                        .taskId(t.getId().toString())
                        .url(t.getUrl())
                        .status(t.getStatus())
                        .error(t.getError())
                        .recordsExtracted(t.getRecordsExtracted())
                        .attemptCount(t.getAttemptCount())
                        .startedAt(t.getStartedAt())
                        .completedAt(t.getCompletedAt())
                        .build())
                .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Long> buildCountMap(UUID jobId) {
        List<Object[]> rows = taskRepository.countByJobIdGroupByStatus(jobId);
        Map<String, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((String) row[0], (Long) row[1]);
        }
        return map;
    }
}
