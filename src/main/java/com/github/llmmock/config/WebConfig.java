package com.github.llmmock.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.github.llmmock.core.Provider;

/** Mounts each provider's controllers under its configured prefix. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LlmMockProperties properties;

    public WebConfig(LlmMockProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        addPrefix(configurer, properties.getPaths().getOpenai(), Provider.OPENAI);
        addPrefix(configurer, properties.getPaths().getAnthropic(), Provider.ANTHROPIC);
        addPrefix(configurer, properties.getPaths().getGemini(), Provider.GEMINI);
        addPrefix(configurer, properties.getPaths().getBedrock(), Provider.BEDROCK);
    }

    private void addPrefix(PathMatchConfigurer configurer, String prefix, Provider provider) {
        if (prefix == null || prefix.isBlank() || "/".equals(prefix)) {
            // Mounted at the root: the controller's own @RequestMapping is already correct.
            return;
        }
        String normalised = prefix.startsWith("/") ? prefix : "/" + prefix;
        configurer.addPathPrefix(normalised, type -> {
            ProviderApi api = AnnotationUtils.findAnnotation(type, ProviderApi.class);
            return api != null && api.value() == provider;
        });
    }
}
