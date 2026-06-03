package org.xmlpipeline.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskStatusResponse {

    @JsonProperty("task_id")
    private String taskId;

    private String url;

    private String status;

    private String error;

    @JsonProperty("records_extracted")
    private int recordsExtracted;

    @JsonProperty("attempt_count")
    private int attemptCount;

    @JsonProperty("started_at")
    private LocalDateTime startedAt;

    @JsonProperty("completed_at")
    private LocalDateTime completedAt;
}
