package com.github.llmmock.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.github.llmmock.store.RequestLogRepository;
import com.github.llmmock.store.StubRuleRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Base class for the HTTP-level tests. Each test starts from an empty stub table and an
 * empty request log so ordering between tests can never matter.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class MockServerTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected StubRuleRepository stubs;

    @Autowired
    protected RequestLogRepository logs;

    @BeforeEach
    void resetState() {
        stubs.deleteAll();
        logs.deleteAll();
    }
}
