package com.example.llmmock.sdk;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.example.llmmock.store.RequestLogRepository;
import com.example.llmmock.store.StubRuleRepository;

/**
 * Base class for the tests that drive the mock through each vendor's real client SDK.
 *
 * <p>These run against a real servlet container on a random port rather than through
 * MockMvc: the point is to exercise the SDKs' own HTTP stacks, SSE parsers and event
 * stream decoders, which is the only way to know the mock satisfies the clients it exists
 * to stand in for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class SdkTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected StubRuleRepository stubs;

    @Autowired
    protected RequestLogRepository logs;

    @BeforeEach
    void resetState() {
        stubs.deleteAll();
        logs.deleteAll();
    }

    protected String baseUrl(String prefix) {
        return "http://localhost:" + port + prefix;
    }
}
