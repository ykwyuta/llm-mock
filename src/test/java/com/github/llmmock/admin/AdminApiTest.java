package com.github.llmmock.admin;

import org.junit.jupiter.api.Test;

import com.github.llmmock.support.MockServerTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminApiTest extends MockServerTest {

    @Test
    void healthReportsTheCurrentCounts() throws Exception {
        mvc.perform(get("/__admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.stubs").value(0))
                .andExpect(jsonPath("$.requests").value(0));
    }

    @Test
    void aStubCanBeCreatedListedUpdatedAndDeleted() throws Exception {
        String created = mvc.perform(post("/__admin/stubs").contentType("application/json")
                        .content("""
                                {"name":"greeting","provider":"OPENAI","promptPattern":"(?i)hello",
                                 "responseText":"Hi there","priority":5}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("greeting"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();

        mvc.perform(get("/__admin/stubs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/__admin/stubs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseText").value("Hi there"));

        mvc.perform(put("/__admin/stubs/{id}", id).contentType("application/json")
                        .content("""
                                {"name":"greeting","provider":"OPENAI","responseText":"Updated"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseText").value("Updated"));

        mvc.perform(delete("/__admin/stubs/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/__admin/stubs/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void postingTheSameNameReplacesTheExistingStubRatherThanDuplicatingIt() throws Exception {
        mvc.perform(post("/__admin/stubs").contentType("application/json")
                        .content("""
                                {"name":"dup","responseText":"first"}"""))
                .andExpect(status().isCreated());

        mvc.perform(post("/__admin/stubs").contentType("application/json")
                        .content("""
                                {"name":"dup","responseText":"second"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseText").value("second"));

        assertThat(stubs.findAll()).hasSize(1);
    }

    @Test
    void anInvalidRegexIsRejectedAtRegistrationTimeRatherThanSilentlyNeverMatching()
            throws Exception {
        mvc.perform(post("/__admin/stubs").contentType("application/json")
                        .content("""
                                {"name":"bad","promptPattern":"[unclosed"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("promptPattern")));

        assertThat(stubs.findAll()).isEmpty();
    }

    @Test
    void aStubWithoutANameIsRejected() throws Exception {
        mvc.perform(post("/__admin/stubs").contentType("application/json")
                        .content("""
                                {"responseText":"anonymous"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void recordedRequestsCanBeFilteredAndInspected() throws Exception {
        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json")
                .content("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":"one"}]}"""));
        mvc.perform(post("/anthropic/v1/messages").contentType("application/json")
                .content("""
                        {"model":"claude-sonnet-4-5","max_tokens":16,
                         "messages":[{"role":"user","content":"two"}]}"""));

        mvc.perform(get("/__admin/requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Newest first.
                .andExpect(jsonPath("$[0].provider").value("ANTHROPIC"));

        mvc.perform(get("/__admin/requests").param("provider", "OPENAI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].model").value("gpt-4o"))
                .andExpect(jsonPath("$[0].endpoint").value("chat.completions"))
                .andExpect(jsonPath("$[0].streaming").value(false))
                .andExpect(jsonPath("$[0].httpStatus").value(200))
                // The exact bytes the caller sent, which is what most assertions need.
                .andExpect(jsonPath("$[0].requestBody")
                        .value(org.hamcrest.Matchers.containsString("\"content\":\"one\"")));

        mvc.perform(get("/__admin/requests").param("model", "no-such-model"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theLimitParameterCapsTheNumberOfRecordsReturned() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/openai/v1/chat/completions").contentType("application/json")
                    .content("""
                            {"model":"gpt-4o","messages":[{"role":"user","content":"x"}]}"""));
        }

        mvc.perform(get("/__admin/requests").param("limit", "2"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void resetClearsBothStubsAndRecordedRequests() throws Exception {
        mvc.perform(post("/__admin/stubs").contentType("application/json")
                .content("""
                        {"name":"temp","responseText":"x"}"""));
        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json")
                .content("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":"x"}]}"""));

        mvc.perform(post("/__admin/reset")).andExpect(status().isNoContent());

        assertThat(stubs.findAll()).isEmpty();
        assertThat(logs.findAll()).isEmpty();
    }

    @Test
    void aStubRegisteredOverHttpImmediatelyAffectsTheProviderEndpoints() throws Exception {
        mvc.perform(post("/__admin/stubs").contentType("application/json")
                        .content("""
                                {"name":"canned","promptPattern":"(?i)ping","responseText":"pong"}"""))
                .andExpect(status().isCreated());

        mvc.perform(post("/openai/v1/chat/completions").contentType("application/json")
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"ping"}]}"""))
                .andExpect(jsonPath("$.choices[0].message.content").value("pong"));
    }
}
