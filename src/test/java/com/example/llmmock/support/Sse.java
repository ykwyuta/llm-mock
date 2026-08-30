package com.example.llmmock.support;

import java.util.ArrayList;
import java.util.List;

/** Parses an SSE body into its events so tests can assert on names and payloads. */
public final class Sse {

    private Sse() {
    }

    public record Event(String name, String data) {
    }

    public static List<Event> parse(String body) {
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            if (block.isBlank()) {
                continue;
            }
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event: ")) {
                    name = line.substring("event: ".length());
                } else if (line.startsWith("data: ")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data: ".length()));
                }
            }
            events.add(new Event(name, data.toString()));
        }
        return events;
    }

    /** Every {@code data:} payload in order, which is what the OpenAI and Gemini tests need. */
    public static List<String> dataLines(String body) {
        return parse(body).stream().map(Event::data).toList();
    }
}
