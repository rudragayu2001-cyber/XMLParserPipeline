package org.xmlpipeline.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.xmlpipeline.dto.*;
import org.xmlpipeline.service.JobService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints:
 *
 *  POST /jobs              – start job with custom URL list
 *  POST /jobs/default      – start job with built-in 100-URL list
 *  GET  /jobs              – list jobs (newest first)
 *  GET  /jobs/{id}         – job status + live counts + elapsed time
 *  GET  /jobs/{id}/tasks   – per-URL task details (?status= filter optional)
 *  GET  /health            – liveness probe
 */
@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
@Slf4j
public class JobController {

    private final JobService    jobService;
    private final ObjectMapper  objectMapper;

    @Value("${app.urls-file:/app/urls.json}")
    private String urlsFile;

    // ── POST /jobs ────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse createJob(@Valid @RequestBody JobCreateRequest request) {
        return jobService.createJob(request.getUrls());
    }

    // ── POST /jobs/default ────────────────────────────────────────────────────

    @PostMapping("/default")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse createDefaultJob() {
        List<String> urls = loadDefaultUrls();
        return jobService.createJob(urls);
    }

    // ── GET /jobs ─────────────────────────────────────────────────────────────

    @GetMapping
    public List<JobResponse> listJobs(
            @RequestParam(defaultValue = "20") int limit) {
        return jobService.listJobs(Math.min(limit, 100));
    }

    // ── GET /jobs/{id} ────────────────────────────────────────────────────────

    @GetMapping("/{jobId}")
    public JobStatusResponse getJobStatus(@PathVariable UUID jobId) {
        return jobService.getJobStatus(jobId);
    }

    // ── GET /jobs/{id}/tasks ──────────────────────────────────────────────────

    @GetMapping("/{jobId}/tasks")
    public List<TaskStatusResponse> getJobTasks(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String status) {
        return jobService.getJobTasks(jobId, status);
    }

    // ── GET /health ───────────────────────────────────────────────────────────

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    // ── Exception handlers ────────────────────────────────────────────────────

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> loadDefaultUrls() {
        try {
            return objectMapper.readValue(new File(urlsFile), List.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load urls.json from " + urlsFile + ": " + e.getMessage(), e);
        }
    }
}
