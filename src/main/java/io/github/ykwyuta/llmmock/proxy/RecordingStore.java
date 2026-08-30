package io.github.ykwyuta.llmmock.proxy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.ykwyuta.llmmock.config.LlmMockProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * Reads and writes recordings on disk, and indexes them by match key.
 *
 * <p>Files are plain JSON named after the provider, the path and the key, so a directory
 * of recordings stays reviewable and a specific one is easy to find and edit by hand.
 */
@Component
public class RecordingStore {

    private static final Logger log = LoggerFactory.getLogger(RecordingStore.class);

    private final LlmMockProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, Recording> index = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public RecordingStore(LlmMockProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public Path directory() {
        return Paths.get(properties.getProxy().getRecordingsDir()).toAbsolutePath().normalize();
    }

    /** Loads the directory once, lazily, so an unused proxy costs nothing at startup. */
    private void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    reload();
                }
            }
        }
    }

    /** Rescans the directory, replacing the in-memory index. */
    public synchronized int reload() {
        index.clear();
        Path dir = directory();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(file -> file.getFileName().toString().endsWith(".json"))
                        .forEach(this::loadOne);
            } catch (IOException ex) {
                log.warn("Could not list recordings in {}: {}", dir, ex.getMessage());
            }
        }
        loaded = true;
        log.info("Loaded {} recording(s) from {}", index.size(), dir);
        return index.size();
    }

    private void loadOne(Path file) {
        try {
            Recording recording = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    Recording.class);
            if (recording.key() == null) {
                log.warn("Recording {} has no key and will be ignored", file.getFileName());
                return;
            }
            Recording previous = index.put(recording.key(), recording);
            if (previous != null) {
                log.warn("Recording key {} appears more than once; {} wins",
                        recording.key(), file.getFileName());
            }
        } catch (RuntimeException | IOException ex) {
            log.warn("Could not read recording {}: {}", file.getFileName(), ex.getMessage());
        }
    }

    public Optional<Recording> find(String key) {
        ensureLoaded();
        return Optional.ofNullable(index.get(key));
    }

    public List<Recording> all() {
        ensureLoaded();
        return new ArrayList<>(index.values());
    }

    public int size() {
        ensureLoaded();
        return index.size();
    }

    /** Writes a recording and makes it immediately available for replay in this process. */
    public Path save(Recording recording) {
        ensureLoaded();
        Path dir = directory();
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName(recording));
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), recording);
            index.put(recording.key(), recording);
            log.info("Recorded {} {} -> {}", recording.request().method(),
                    recording.request().path(), file.getFileName());
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not write recording to " + dir, ex);
        }
    }

    public synchronized void deleteAll() {
        Path dir = directory();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(file -> file.getFileName().toString().endsWith(".json"))
                        .forEach(file -> {
                            try {
                                Files.deleteIfExists(file);
                            } catch (IOException ex) {
                                log.warn("Could not delete {}: {}", file, ex.getMessage());
                            }
                        });
            } catch (IOException ex) {
                log.warn("Could not list recordings in {}: {}", dir, ex.getMessage());
            }
        }
        index.clear();
        loaded = true;
    }

    static String fileName(Recording recording) {
        String provider = recording.provider() == null ? "unknown"
                : recording.provider().name().toLowerCase(Locale.ROOT);
        return provider + "__" + slug(recording.request().path()) + "__" + recording.key() + ".json";
    }

    /** Turns a URL path into a short, filesystem-safe fragment for the file name. */
    static String slug(String path) {
        if (path == null || path.isBlank()) {
            return "root";
        }
        String cleaned = path.replaceAll("^/+", "").replaceAll("[^A-Za-z0-9._:-]+", "-");
        cleaned = cleaned.replaceAll("^-+", "").replaceAll("-+$", "");
        if (cleaned.isEmpty()) {
            return "root";
        }
        return cleaned.length() <= 60 ? cleaned : cleaned.substring(cleaned.length() - 60);
    }
}
