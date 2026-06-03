package org.xmlpipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class XmlPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(XmlPipelineApplication.class, args);
    }
}