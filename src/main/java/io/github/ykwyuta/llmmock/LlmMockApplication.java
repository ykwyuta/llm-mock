package io.github.ykwyuta.llmmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import io.github.ykwyuta.llmmock.config.LlmMockProperties;

@SpringBootApplication
@EnableConfigurationProperties(LlmMockProperties.class)
public class LlmMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmMockApplication.class, args);
    }
}
