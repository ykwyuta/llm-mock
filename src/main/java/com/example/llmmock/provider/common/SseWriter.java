package com.example.llmmock.provider.common;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import tools.jackson.databind.ObjectMapper;

/** Minimal Server-Sent Events encoder shared by the OpenAI, Anthropic and Gemini adapters. */
public class SseWriter {

    private final OutputStream out;
    private final ObjectMapper mapper;

    public SseWriter(OutputStream out, ObjectMapper mapper) {
        this.out = out;
        this.mapper = mapper;
    }

    /** Emits {@code data: <json>} with no event name (OpenAI and Gemini style). */
    public void data(Object payload) throws IOException {
        write(null, mapper.writeValueAsString(payload));
    }

    /** Emits {@code event: <name>} followed by {@code data: <json>} (Anthropic style). */
    public void event(String name, Object payload) throws IOException {
        write(name, mapper.writeValueAsString(payload));
    }

    /** Emits a raw, already-encoded data payload such as OpenAI's {@code [DONE]} sentinel. */
    public void raw(String data) throws IOException {
        write(null, data);
    }

    private void write(String eventName, String data) throws IOException {
        StringBuilder frame = new StringBuilder();
        if (eventName != null) {
            frame.append("event: ").append(eventName).append('\n');
        }
        frame.append("data: ").append(data).append("\n\n");
        out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
