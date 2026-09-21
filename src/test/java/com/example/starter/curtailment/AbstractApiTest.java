package com.example.starter.curtailment;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 测试基类：提供 MockMvc 与数据库清理，每个用例独立数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractApiTest {

    protected static final String FROM = "2026-10-01T00:00:00Z";
    protected static final String TO = "2026-10-02T00:00:00Z";
    protected static final String EXEC_FROM = "2026-10-01T10:00:00Z";
    protected static final String EXEC_TO = "2026-10-01T12:00:00Z";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM dispatch_event");
        jdbc.update("DELETE FROM dispatch_allocation");
        jdbc.update("DELETE FROM curtailment_dispatch");
        jdbc.update("DELETE FROM capacity_commitment");
        jdbc.update("DELETE FROM idempotent_command");
    }

    protected ResultActions postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions putJson(String url, String body) throws Exception {
        return mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions getJson(String url) throws Exception {
        return mockMvc.perform(get(url));
    }

    protected String createCommitment(String commandKey, String commitmentKey, String siteId,
                                      String validFrom, String validTo, String maxPowerKw) throws Exception {
        String body = """
                {"commandKey":"%s","commitmentKey":"%s","siteId":"%s",
                 "validFrom":"%s","validTo":"%s","maxPowerKw":"%s"}
                """.formatted(commandKey, commitmentKey, siteId, validFrom, validTo, maxPowerKw);
        return postJson("/api/commitments", body)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    protected String createDispatch(String commandKey, String dispatchKey, String feederId,
                                    String targetPowerKw, String allocationsJson) throws Exception {
        String body = """
                {"commandKey":"%s","dispatchKey":"%s","feederId":"%s",
                 "executeFrom":"%s","executeTo":"%s","targetPowerKw":"%s","allocations":%s}
                """.formatted(commandKey, dispatchKey, feederId, EXEC_FROM, EXEC_TO, targetPowerKw,
                allocationsJson);
        return postJson("/api/dispatches", body)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    protected String publish(String commandKey, String dispatchKey) throws Exception {
        return postJson("/api/dispatches/" + dispatchKey + "/publish", "{\"commandKey\":\"" + commandKey + "\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    protected String cancel(String commandKey, String dispatchKey) throws Exception {
        return postJson("/api/dispatches/" + dispatchKey + "/cancel", "{\"commandKey\":\"" + commandKey + "\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
