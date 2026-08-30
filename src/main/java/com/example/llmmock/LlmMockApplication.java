package com.example.llmmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.example.llmmock.config.LlmMockProperties;

@SpringBootApplication
@EnableConfigurationProperties(LlmMockProperties.class)
public class LlmMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmMockApplication.class, args);
    }
}
