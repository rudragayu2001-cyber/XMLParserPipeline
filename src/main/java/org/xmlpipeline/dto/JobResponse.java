package org.xmlpipeline.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class JobResponse{

    @JsonProperty("job_id")
    private String jobId;

    private String status;

    @JsonProperty("total_urls")
    private int totalUrls;

    @JsonProperty("created_at")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LocalDateTime createdAt;

    public JobResponse(String jobId, String status, int totalUrls){
        this(jobId,status,totalUrls,null);
    }
}