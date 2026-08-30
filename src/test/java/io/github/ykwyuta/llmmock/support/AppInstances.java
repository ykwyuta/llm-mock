package io.github.ykwyuta.llmmock.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.github.ykwyuta.llmmock.LlmMockApplication;

/**
 * Starts and stops application instances for the tests that need more than one - a proxy
 * and the upstream it points at. Each instance gets a random port and its own in-memory
 * database so they cannot see each other's stubs.
 */
public class AppInstances implements AutoCloseable {

    private final List<ConfigurableApplicationContext> started = new ArrayList<>();

    public ConfigurableApplicationContext start(Map<String, String> properties) {
        return start(new Class<?>[] {LlmMockApplication.class}, properties);
    }

    /** Extra sources let a test add an observation bean the production app has no reason to carry. */
    public ConfigurableApplicationContext start(Class<?>[] sources, Map<String, String> properties) {
        List<String> args = new ArrayList<>();
        // Command-line args rather than SpringApplicationBuilder#properties: the latter
        // become default properties, which application.yml (server.port: 8080) overrides.
        args.add("--server.port=0");
        args.add("--spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        args.add("--spring.main.banner-mode=off");
        args.add("--logging.level.root=WARN");
        properties.forEach((key, value) -> args.add("--" + key + "=" + value));

        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(sources).run(args.toArray(String[]::new));
        started.add(context);
        return context;
    }

    public static String urlOf(ConfigurableApplicationContext context) {
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    /** Stops everything, newest first, so a proxy never outlives the upstream it points at. */
    public void stopAll() {
        for (int i = started.size() - 1; i >= 0; i--) {
            started.get(i).close();
        }
        started.clear();
    }

    @Override
    public void close() {
        stopAll();
    }

    /** Convenience for building the property map inline. */
    public static Map<String, String> properties(String... keyValuePairs) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            properties.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return properties;
    }
}
