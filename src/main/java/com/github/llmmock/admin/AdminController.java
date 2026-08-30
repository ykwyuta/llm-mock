package com.github.llmmock.admin;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.llmmock.config.LlmMockProperties;
import com.github.llmmock.core.MockApiException;
import com.github.llmmock.core.Provider;
import com.github.llmmock.proxy.Recording;
import com.github.llmmock.proxy.RecordingStore;
import com.github.llmmock.store.RequestLogRepository;
import com.github.llmmock.store.StubRule;
import com.github.llmmock.store.StubRuleRepository;
import com.github.llmmock.usage.UsageRecord;
import com.github.llmmock.usage.UsageRepository;
import com.github.llmmock.usage.UsageSource;
import com.github.llmmock.usage.UsageSummary;
import com.github.llmmock.usage.UsageTracker;

import jakarta.validation.Valid;

/**
 * Control plane. A test suite drives the mock entirely through this: register stubs before
 * exercising the application, then read the recorded requests back to assert on them.
 *
 * <p>It is deliberately mounted outside every provider prefix so it can never collide with
 * a real provider path.
 */
@RestController
@RequestMapping("/__admin")
public class AdminController {

    private final StubRuleRepository stubs;
    private final RequestLogRepository logs;
    private final RecordingStore recordings;
    private final UsageRepository usage;
    private final UsageTracker usageTracker;
    private final LlmMockProperties properties;

    public AdminController(StubRuleRepository stubs, RequestLogRepository logs,
                           RecordingStore recordings, UsageRepository usage,
                           UsageTracker usageTracker, LlmMockProperties properties) {
        this.stubs = stubs;
        this.logs = logs;
        this.recordings = recordings;
        this.usage = usage;
        this.usageTracker = usageTracker;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "stubs", stubs.count(), "requests", logs.count(),
                "mode", properties.getMode(), "recordings", recordings.size(),
                "usageRecords", usage.count());
    }

    // --- token usage and cost --------------------------------------------------------

    /** The individual calls, newest first. */
    @GetMapping("/usage")
    public List<UsageRecord> listUsage(@RequestParam(required = false) Provider provider,
                                       @RequestParam(required = false) String model,
                                       @RequestParam(required = false) UsageSource source,
                                       @RequestParam(defaultValue = "100") int limit) {
        Provider providerFilter = provider == Provider.ANY ? null : provider;
        return usage.search(providerFilter, model, source,
                PageRequest.of(0, Math.max(1, limit)));
    }

    /**
     * Cost report, aggregated per model. {@code totals.upstreamCost} is what was actually
     * spent; {@code totals.cacheSavings} is what the cache hits would have cost.
     */
    @GetMapping("/usage/summary")
    public UsageSummary usageSummary(@RequestParam(required = false) Provider provider,
                                     @RequestParam(required = false) UsageSource source) {
        return usageTracker.summarise(provider == Provider.ANY ? null : provider, source);
    }

    @DeleteMapping("/usage")
    public ResponseEntity<Void> deleteUsage() {
        usageTracker.deleteAll();
        return ResponseEntity.noContent().build();
    }

    // --- recordings ------------------------------------------------------------------

    /** Lists the recordings currently indexed, without their bodies. */
    @GetMapping("/recordings")
    public Map<String, Object> listRecordings() {
        List<Map<String, Object>> entries = recordings.all().stream()
                .map(recording -> {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("key", recording.key());
                    entry.put("provider", recording.provider());
                    entry.put("method", recording.request().method());
                    entry.put("path", recording.request().path());
                    entry.put("query", recording.request().query());
                    entry.put("status", recording.response().status());
                    entry.put("recordedAt", recording.recordedAt());
                    return entry;
                })
                .toList();
        return Map.of("directory", recordings.directory().toString(),
                "count", entries.size(), "recordings", entries);
    }

    @GetMapping("/recordings/{key}")
    public Recording getRecording(@PathVariable String key) {
        return recordings.find(key)
                .orElseThrow(() -> MockApiException.notFound("No recording with key " + key));
    }

    /** Rescans the recordings directory, picking up files added since startup. */
    @PostMapping("/recordings/reload")
    public Map<String, Object> reloadRecordings() {
        int count = recordings.reload();
        return Map.of("directory", recordings.directory().toString(), "count", count);
    }

    @DeleteMapping("/recordings")
    public ResponseEntity<Void> deleteRecordings() {
        recordings.deleteAll();
        return ResponseEntity.noContent().build();
    }

    // --- stubs -----------------------------------------------------------------------

    @GetMapping("/stubs")
    public List<StubRuleDto> listStubs() {
        return stubs.findAll().stream().map(StubRuleDto::from).toList();
    }

    @GetMapping("/stubs/{id}")
    public StubRuleDto getStub(@PathVariable Long id) {
        return StubRuleDto.from(require(id));
    }

    /** Creates a stub, or replaces the existing one with the same name. */
    @PostMapping("/stubs")
    public ResponseEntity<StubRuleDto> createStub(@Valid @RequestBody StubRuleDto dto) {
        validatePatterns(dto);
        StubRule rule = stubs.findByName(dto.name()).orElseGet(StubRule::new);
        boolean isNew = rule.getId() == null;
        dto.applyTo(rule);
        StubRuleDto saved = StubRuleDto.from(stubs.save(rule));
        return ResponseEntity.status(isNew ? HttpStatus.CREATED : HttpStatus.OK).body(saved);
    }

    @PutMapping("/stubs/{id}")
    public StubRuleDto updateStub(@PathVariable Long id, @Valid @RequestBody StubRuleDto dto) {
        validatePatterns(dto);
        StubRule rule = require(id);
        dto.applyTo(rule);
        return StubRuleDto.from(stubs.save(rule));
    }

    @DeleteMapping("/stubs/{id}")
    public ResponseEntity<Void> deleteStub(@PathVariable Long id) {
        stubs.delete(require(id));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/stubs")
    public ResponseEntity<Void> deleteAllStubs() {
        stubs.deleteAll();
        return ResponseEntity.noContent().build();
    }

    // --- recorded requests -----------------------------------------------------------

    @GetMapping("/requests")
    public List<RequestLogDto> listRequests(@RequestParam(required = false) Provider provider,
                                            @RequestParam(required = false) String model,
                                            @RequestParam(required = false) String endpoint,
                                            @RequestParam(defaultValue = "100") int limit) {
        Provider filter = provider == Provider.ANY ? null : provider;
        return logs.search(filter, model, endpoint, PageRequest.of(0, Math.max(1, limit))).stream()
                .map(RequestLogDto::from)
                .toList();
    }

    @DeleteMapping("/requests")
    public ResponseEntity<Void> deleteRequests() {
        logs.deleteAll();
        return ResponseEntity.noContent().build();
    }

    /** Clears stubs and recorded requests. Handy in a JUnit {@code @BeforeEach}. */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        stubs.deleteAll();
        logs.deleteAll();
        usageTracker.deleteAll();
        return ResponseEntity.noContent().build();
    }

    // --- helpers ---------------------------------------------------------------------

    private StubRule require(Long id) {
        return stubs.findById(id)
                .orElseThrow(() -> MockApiException.notFound("No stub with id " + id));
    }

    /** Fail loudly at registration time rather than silently never matching. */
    private void validatePatterns(StubRuleDto dto) {
        compile(dto.modelPattern(), "modelPattern");
        compile(dto.promptPattern(), "promptPattern");
        compile(dto.endpointPattern(), "endpointPattern");
    }

    private void compile(String pattern, String field) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException ex) {
            throw MockApiException.invalidRequest(field + " is not a valid regex: " + ex.getMessage());
        }
    }
}
