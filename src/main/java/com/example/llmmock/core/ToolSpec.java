package com.example.llmmock.core;

import java.util.Map;

/** A tool/function declaration as offered by the caller. */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {
}
