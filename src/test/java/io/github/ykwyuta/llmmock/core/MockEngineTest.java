package io.github.ykwyuta.llmmock.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import io.github.ykwyuta.llmmock.store.RequestLogRepository;
import io.github.ykwyuta.llmmock.store.StubRule;
import io.github.ykwyuta.llmmock.store.StubRuleRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the resolution order the whole mock is built on: header, then stub, then default. */
@SpringBootTest
@ActiveProfiles("test")
class MockEngineTest {

    @Autowired
    private MockEngine engine;

    @Autowired
    private StubRuleRepository stubs;

    @Autowired
    private RequestLogRepository logs;

    @BeforeEach
    void reset() {
        stubs.deleteAll();
        logs.deleteAll();
    }

    private MockRequest request(Provider provider, String model, String prompt) {
        return MockRequest.builder(provider, "chat.completions")
                .model(model)
                .message(ChatRole.USER, prompt)
                .build();
    }

    private StubRule stub(String name) {
        StubRule rule = new StubRule();
        rule.setName(name);
        rule.setResponseText("from " + name);
        return rule;
    }

    @Test
    void withoutStubsTheConfiguredTemplateAnswers() {
        MockCompletion completion = engine.complete(request(Provider.OPENAI, "gpt-4o", "hello"),
                MockOverrides.NONE);

        assertThat(completion.text()).isEqualTo("[llm-mock] echo: hello");
        assertThat(completion.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(completion.matchedStub()).isNull();
    }

    @Test
    void aMatchingStubBeatsTheDefault() {
        stubs.save(stub("greeting"));

        MockCompletion completion = engine.complete(request(Provider.OPENAI, "gpt-4o", "hello"),
                MockOverrides.NONE);

        assertThat(completion.text()).isEqualTo("from greeting");
        assertThat(completion.matchedStub()).isEqualTo("greeting");
    }

    @Test
    void aHeaderOverrideBeatsAMatchingStub() {
        stubs.save(stub("greeting"));
        MockOverrides overrides = new MockOverrides("forced", null, null, null, null, null, null,
                null, null, null, null);

        MockCompletion completion = engine.complete(request(Provider.OPENAI, "gpt-4o", "hello"),
                overrides);

        assertThat(completion.text()).isEqualTo("forced");
        // The stub still matched, so a test can assert which rule was in play.
        assertThat(completion.matchedStub()).isEqualTo("greeting");
    }

    @Test
    void higherPriorityWinsAndTiesGoToTheOlderRule() {
        StubRule low = stub("low");
        StubRule high = stub("high");
        high.setPriority(10);
        stubs.save(low);
        stubs.save(high);

        assertThat(engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), MockOverrides.NONE).text())
                .isEqualTo("from high");

        stubs.delete(stubs.findByName("high").orElseThrow());
        StubRule samePriority = stub("later");
        stubs.save(samePriority);

        assertThat(engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), MockOverrides.NONE).text())
                .isEqualTo("from low");
    }

    @Test
    void providerModelAndPromptCriteriaAllHaveToMatch() {
        StubRule rule = stub("narrow");
        rule.setProvider(Provider.ANTHROPIC);
        rule.setModelPattern("^claude-");
        rule.setPromptPattern("weather");
        stubs.save(rule);

        assertThat(engine.complete(request(Provider.ANTHROPIC, "claude-sonnet-4-5", "the weather?"),
                MockOverrides.NONE).text()).isEqualTo("from narrow");
        // Wrong provider.
        assertThat(engine.complete(request(Provider.OPENAI, "claude-sonnet-4-5", "the weather?"),
                MockOverrides.NONE).matchedStub()).isNull();
        // Wrong model.
        assertThat(engine.complete(request(Provider.ANTHROPIC, "gpt-4o", "the weather?"),
                MockOverrides.NONE).matchedStub()).isNull();
        // Wrong prompt.
        assertThat(engine.complete(request(Provider.ANTHROPIC, "claude-sonnet-4-5", "the time?"),
                MockOverrides.NONE).matchedStub()).isNull();
    }

    @Test
    void anAnyProviderStubServesEveryProtocol() {
        StubRule rule = stub("shared");
        rule.setProvider(Provider.ANY);
        stubs.save(rule);

        for (Provider provider : new Provider[] {Provider.OPENAI, Provider.ANTHROPIC,
                Provider.GEMINI, Provider.BEDROCK}) {
            assertThat(engine.complete(request(provider, "any-model", "x"), MockOverrides.NONE).text())
                    .as("provider %s", provider)
                    .isEqualTo("from shared");
        }
    }

    @Test
    void aStubWithLimitedUsesStopsMatchingOnceConsumed() {
        StubRule rule = stub("once");
        rule.setRemainingUses(1);
        stubs.save(rule);

        assertThat(engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), MockOverrides.NONE).text())
                .isEqualTo("from once");
        assertThat(engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), MockOverrides.NONE).text())
                .isEqualTo("[llm-mock] echo: x");
    }

    @Test
    void anErrorStubWithLimitedUsesStillBurnsItsUse() {
        // The failure aborts the engine's transaction, so the decrement has to be committed
        // separately or a "fail once, then recover" rule would fail forever.
        StubRule rule = stub("fail-once");
        rule.setHttpStatus(503);
        rule.setRemainingUses(1);
        stubs.save(rule);

        assertThatThrownBy(() -> engine.complete(request(Provider.OPENAI, "gpt-4o", "x"),
                MockOverrides.NONE)).isInstanceOf(MockApiException.class);

        assertThat(engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), MockOverrides.NONE)
                .matchedStub()).isNull();
    }

    @Test
    void aDisabledStubNeverMatches() {
        StubRule rule = stub("off");
        rule.setEnabled(false);
        stubs.save(rule);

        assertThat(engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), MockOverrides.NONE)
                .matchedStub()).isNull();
    }

    @Test
    void anInvalidRegexIsSkippedRatherThanFailingTheRequest() {
        StubRule rule = stub("broken");
        rule.setPromptPattern("[unclosed");
        stubs.save(rule);

        assertThat(engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), MockOverrides.NONE)
                .matchedStub()).isNull();
    }

    @Test
    void anErrorStubRaisesTheSimulatedFailure() {
        StubRule rule = stub("throttled");
        rule.setHttpStatus(429);
        rule.setErrorMessage("slow down");
        stubs.save(rule);

        assertThatThrownBy(() -> engine.complete(request(Provider.OPENAI, "gpt-4o", "x"),
                MockOverrides.NONE))
                .isInstanceOfSatisfying(MockApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(429);
                    assertThat(ex.type()).isEqualTo("rate_limit");
                    assertThat(ex.getMessage()).isEqualTo("slow down");
                });
    }

    @Test
    void selectingAStubByNameSkipsMatchingEntirely() {
        StubRule general = stub("general");
        general.setPriority(100);
        stubs.save(general);
        stubs.save(stub("special"));

        MockOverrides overrides = new MockOverrides(null, null, null, null, null, null, null, null,
                null, null, "special");

        assertThat(engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), overrides).text())
                .isEqualTo("from special");
    }

    @Test
    void namingAStubThatDoesNotExistIsA404() {
        MockOverrides overrides = new MockOverrides(null, null, null, null, null, null, null, null,
                null, null, "missing");

        assertThatThrownBy(() -> engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), overrides))
                .isInstanceOfSatisfying(MockApiException.class,
                        ex -> assertThat(ex.status()).isEqualTo(404));
    }

    @Test
    void toolCallsSwitchTheFinishReasonToToolUse() {
        StubRule rule = stub("weather-tool");
        rule.setToolName("get_weather");
        rule.setToolArguments("{\"city\":\"Tokyo\"}");
        stubs.save(rule);

        MockCompletion completion = engine.complete(request(Provider.OPENAI, "gpt-4o", "x"),
                MockOverrides.NONE);

        assertThat(completion.finishReason()).isEqualTo(FinishReason.TOOL_USE);
        assertThat(completion.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("get_weather");
            assertThat(call.arguments()).isEqualTo("{\"city\":\"Tokyo\"}");
        });
    }

    @Test
    void explicitTokenCountsOverrideTheEstimate() {
        MockOverrides overrides = new MockOverrides(null, null, null, null, null, null, null, null,
                111, 222, null);

        MockCompletion completion = engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), overrides);

        assertThat(completion.usage().inputTokens()).isEqualTo(111);
        assertThat(completion.usage().outputTokens()).isEqualTo(222);
        assertThat(completion.usage().totalTokens()).isEqualTo(333);
    }

    @Test
    void everyCompletionIsRecorded() {
        engine.complete(request(Provider.GEMINI, "gemini-2.5-pro", "hello"), MockOverrides.NONE);

        assertThat(logs.findAll()).singleElement().satisfies(entry -> {
            assertThat(entry.getProvider()).isEqualTo(Provider.GEMINI);
            assertThat(entry.getModel()).isEqualTo("gemini-2.5-pro");
            assertThat(entry.getHttpStatus()).isEqualTo(200);
            assertThat(entry.getResponseText()).isEqualTo("[llm-mock] echo: hello");
        });
    }

    @Test
    void simulatedFailuresAreRecordedWithTheirStatus() {
        MockOverrides overrides = new MockOverrides(null, null, 503, null, "down", null, null, null,
                null, null, null);

        assertThatThrownBy(() -> engine.complete(request(Provider.OPENAI, "gpt-4o", "x"), overrides))
                .isInstanceOf(MockApiException.class);

        assertThat(logs.findAll()).singleElement()
                .satisfies(entry -> assertThat(entry.getHttpStatus()).isEqualTo(503));
    }
}
