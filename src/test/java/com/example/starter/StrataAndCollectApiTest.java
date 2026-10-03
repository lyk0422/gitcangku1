package com.example.starter;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 层位登记与样本采集 API 测试：真实 H2 库，可控时钟验证 UTC 固化，
 * 覆盖主流程、重复编号、幂等重放与异参冲突。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_collect;MODE=MySQL")
@AutoConfigureMockMvc
class StrataAndCollectApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM package_item");
        jdbc.update("DELETE FROM seal_event");
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM sample");
        jdbc.update("DELETE FROM seal_package");
        jdbc.update("DELETE FROM stratum");
        Mockito.when(clock.instant()).thenAnswer(inv -> Instant.now());
    }

    @Test
    void registerStratumAndRejectDuplicate() throws Exception {
        String body = """
                {"siteCode":"SITE-A","stratumNo":"L1","description":"合成层位"}
                """;
        mvc.perform(post("/api/strata").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.siteCode").value("SITE-A"))
                .andExpect(jsonPath("$.stratumNo").value("L1"));
        mvc.perform(post("/api/strata").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STRATUM_EXISTS"));
    }

    @Test
    void collectFixesUtcInstantAndRejectsDuplicateSampleNo() throws Exception {
        Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-02-01T08:30:00Z"));
        registerStratum("SITE-A", "L1");
        String body = """
                {"requestId":"req-c-1","sampleNo":"SMP-001","siteCode":"SITE-A","stratumNo":"L1","gridRef":"G-01-01"}
                """;
        mvc.perform(post("/api/samples").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.collectedAt").value("2026-02-01T08:30:00Z"))
                .andExpect(jsonPath("$.gridRef").value("G-01-01"))
                .andExpect(jsonPath("$.status").value("COLLECTED"));
        String duplicate = """
                {"requestId":"req-c-2","sampleNo":"SMP-001","siteCode":"SITE-A","stratumNo":"L1","gridRef":"G-09-09"}
                """;
        mvc.perform(post("/api/samples").contentType(MediaType.APPLICATION_JSON).content(duplicate))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAMPLE_EXISTS"));
    }

    @Test
    void collectIdempotentReplayAndDifferentParamsConflict() throws Exception {
        registerStratum("SITE-A", "L1");
        String body = """
                {"requestId":"req-r-1","sampleNo":"SMP-100","siteCode":"SITE-A","stratumNo":"L1","gridRef":"G-02-02"}
                """;
        String first = mvc.perform(post("/api/samples").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/samples").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay, "同参重放必须返回首次响应");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample WHERE sample_no = 'SMP-100'", Integer.class);
        assertEquals(1, count, "重放不得产生第二条样本");
        String different = """
                {"requestId":"req-r-1","sampleNo":"SMP-101","siteCode":"SITE-A","stratumNo":"L1","gridRef":"G-02-03"}
                """;
        mvc.perform(post("/api/samples").contentType(MediaType.APPLICATION_JSON).content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void collectUnknownStratumReturns422AndValidationReturns400() throws Exception {
        String body = """
                {"requestId":"req-c-9","sampleNo":"SMP-900","siteCode":"NOPE","stratumNo":"L9","gridRef":"G-01"}
                """;
        mvc.perform(post("/api/samples").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("STRATUM_NOT_FOUND"));
        String invalid = """
                {"sampleNo":"SMP-901","siteCode":"SITE-A","stratumNo":"L1","gridRef":"G-01"}
                """;
        mvc.perform(post("/api/samples").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void querySampleShowsStratumGridAndEmptyPackage() throws Exception {
        registerStratum("SITE-A", "L1");
        String body = """
                {"requestId":"req-q-1","sampleNo":"SMP-200","siteCode":"SITE-A","stratumNo":"L1","gridRef":"G-03-03"}
                """;
        mvc.perform(post("/api/samples").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/samples/SMP-200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stratumNo").value("L1"))
                .andExpect(jsonPath("$.gridRef").value("G-03-03"))
                .andExpect(jsonPath("$.packageNo").value(nullValue()));
        mvc.perform(get("/api/samples/SMP-NONE")).andExpect(status().isNotFound());
    }

    private void registerStratum(String siteCode, String stratumNo) throws Exception {
        String body = """
                {"siteCode":"%s","stratumNo":"%s"}
                """.formatted(siteCode, stratumNo);
        mvc.perform(post("/api/strata").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}