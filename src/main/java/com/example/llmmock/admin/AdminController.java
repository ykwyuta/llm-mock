package com.example.llmmock.admin;

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

import com.example.llmmock.core.MockApiException;
import com.example.llmmock.core.Provider;
import com.example.llmmock.store.RequestLogRepository;
import com.example.llmmock.store.StubRule;
import com.example.llmmock.store.StubRuleRepository;

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

    public AdminController(StubRuleRepository stubs, RequestLogRepository logs) {
        this.stubs = stubs;
        this.logs = logs;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "stubs", stubs.count(), "requests", logs.count());
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
