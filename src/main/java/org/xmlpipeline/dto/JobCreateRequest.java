package org.xmlpipeline.dto;


import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class JobCreateRequest{

    @NotEmpty(message = "urls list cannot be empty")
    private List<String> urls;
}