package org.xmlpipeline.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobStatusResponse {

    @JsonProperty("job_id")
    private String jobId;

    private String status;

    @JsonProperty("total_urls")
    private int totalUrls;

    private long completed;

    private long failed;

    @JsonProperty("in_progress")
    private long inProgress;

    private long pending;

    @JsonProperty("elapsed_seconds")
    private Double elapsedSeconds;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("started_at")
    private LocalDateTime startedAt;

    @JsonProperty("completed_at")
    private LocalDateTime completedAt;
}
