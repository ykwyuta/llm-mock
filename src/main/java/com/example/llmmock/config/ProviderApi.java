package com.example.llmmock.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.web.bind.annotation.RestController;

import com.example.llmmock.core.Provider;

/**
 * Marks a controller as serving one provider's protocol. {@link WebConfig} reads it to
 * mount the controller under that provider's configured URL prefix, and the error
 * handlers read it to pick the right error envelope.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RestController
public @interface ProviderApi {

    Provider value();
}
