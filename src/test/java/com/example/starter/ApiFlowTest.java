package com.example.starter;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * REST 层流程测试：一条合成业务调用链（建批→加卷→测色→放行→查询），
 * 以及 422 拒绝与 409 幂等冲突的 HTTP 映射。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_errata");
        jdbc.update("DELETE FROM release_line");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM recheck_ruling");
        jdbc.update("DELETE FROM color_measurement");
        jdbc.update("DELETE FROM batch_roll");
        jdbc.update("DELETE FROM dye_batch");
    }

    private long postAndGetId(String url, String body, String jsonPath) throws Exception {
        MvcResult result = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), jsonPath)).longValue();
    }

    @Test
    void syntheticBusinessCallChain() throws Exception {
        // 合成业务调用：目标 (50,0,0)，布卷 (51,1,1)，色差约 1.732，整批放行
        long batchId = postAndGetId("/api/batches",
                "{\"batchNo\":\"B-API-001\",\"targetL\":50,\"targetA\":0,\"targetB\":0}", "$.batchId");
        long rollId = postAndGetId("/api/batches/" + batchId + "/rolls",
                "{\"rollNo\":\"R-01\"}", "$.rollId");

        mvc.perform(post("/api/rolls/" + rollId + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"m-001\",\"kind\":\"ORIGINAL\",\"l\":51,\"a\":1,\"b\":1,"
                                + "\"measuredAt\":\"2026-10-04T01:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qualified", is(true)))
                .andExpect(jsonPath("$.deltaE", closeTo(1.7320508, 1e-6)))
                .andExpect(jsonPath("$.replayed", is(false)));

        mvc.perform(post("/api/batches/" + batchId + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rel-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetVersion", is(1)))
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].rollNo", is("R-01")))
                .andExpect(jsonPath("$.lines[0].adoptedKind", is("ORIGINAL")))
                .andExpect(jsonPath("$.lines[0].deltaE", closeTo(1.7320508, 1e-6)));

        mvc.perform(get("/api/batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RELEASED")))
                .andExpect(jsonPath("$.targetVersion", is(1)))
                .andExpect(jsonPath("$.rolls[0].original.qualified", is(true)))
                .andExpect(jsonPath("$.rolls[0].adoptedKind", is("ORIGINAL")))
                .andExpect(jsonPath("$.release.lines", hasSize(1)));
    }

    @Test
    void rejectedReleaseReturns422WithRollNumbers() throws Exception {
        long batchId = postAndGetId("/api/batches",
                "{\"batchNo\":\"B-API-002\",\"targetL\":50,\"targetA\":0,\"targetB\":0}", "$.batchId");
        long rollId = postAndGetId("/api/batches/" + batchId + "/rolls",
                "{\"rollNo\":\"R-09\"}", "$.rollId");
        postAndGetId("/api/rolls/" + rollId + "/measurements",
                "{\"requestId\":\"m-002\",\"kind\":\"ORIGINAL\",\"l\":53,\"a\":0,\"b\":0,"
                        + "\"measuredAt\":\"2026-10-04T01:00:00Z\"}", "$.measurementId");

        mvc.perform(post("/api/batches/" + batchId + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rel-002\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail['R-09'][0]", is("OUT_OF_TOLERANCE")));
    }

    @Test
    void idempotentReplayAndConflictOverHttp() throws Exception {
        long batchId = postAndGetId("/api/batches",
                "{\"batchNo\":\"B-API-003\",\"targetL\":50,\"targetA\":0,\"targetB\":0}", "$.batchId");
        long rollId = postAndGetId("/api/batches/" + batchId + "/rolls",
                "{\"rollNo\":\"R-01\"}", "$.rollId");
        String body = "{\"requestId\":\"m-003\",\"kind\":\"ORIGINAL\",\"l\":51,\"a\":1,\"b\":1,"
                + "\"measuredAt\":\"2026-10-04T01:00:00Z\"}";

        mvc.perform(post("/api/rolls/" + rollId + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(false)));
        // 同参重放
        mvc.perform(post("/api/rolls/" + rollId + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)));
        // 异参 409
        mvc.perform(post("/api/rolls/" + rollId + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"m-003\",\"kind\":\"ORIGINAL\",\"l\":52,\"a\":1,\"b\":1,"
                                + "\"measuredAt\":\"2026-10-04T01:00:00Z\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void validationFailureReturns400() throws Exception {
        mvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"\",\"targetL\":50,\"targetA\":0,\"targetB\":0}"))
                .andExpect(status().isBadRequest());
    }
}