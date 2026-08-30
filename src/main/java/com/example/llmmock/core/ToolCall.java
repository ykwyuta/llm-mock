package com.example.llmmock.core;

/**
 * A tool invocation produced by the mock.
 *
 * @param arguments the arguments as a raw JSON object string
 */
public record ToolCall(String id, String name, String arguments) {
}
