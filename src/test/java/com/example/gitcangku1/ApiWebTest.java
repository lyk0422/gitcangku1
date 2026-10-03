package com.example.gitcangku1;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Web 层冒烟：真实 H2 + MockMvc，验证状态码与响应体。 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiWebTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_errata");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM recheck_adjudication");
        jdbc.update("DELETE FROM color_measurement");
        jdbc.update("DELETE FROM dye_batch");
    }

    private String createBatch(String batchNo, int rolls) throws Exception {
        var result = mvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"" + batchNo
                                + "\",\"targetL\":50,\"targetA\":0,\"targetB\":0,\"expectedRolls\":"
                                + rolls + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetVersion").value(1))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.replaceAll(".*\"id\":(\\d+).*", "$1");
    }

    @Test
    void fullChainOverHttp() throws Exception {
        String id = createBatch("B-" + UUID.randomUUID(), 1);
        mvc.perform(post("/api/batches/" + id + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"m-1\",\"rollNo\":1,\"type\":\"ORIGINAL\","
                                + "\"l\":51,\"a\":1,\"b\":1,\"measuredAt\":\"2026-10-01T08:00:00Z\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches/" + id + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].deltaE", closeTo(1.7320508, 1e-6)));
        mvc.perform(get("/api/batches/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.rolls[0].adoptedSource").value("ORIGINAL"));
    }

    @Test
    void rejectionReturns422WithRollNumbers() throws Exception {
        String id = createBatch("B-" + UUID.randomUUID(), 2);
        mvc.perform(post("/api/batches/" + id + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"m-1\",\"rollNo\":1,\"type\":\"ORIGINAL\","
                                + "\"l\":53,\"a\":0,\"b\":0,\"measuredAt\":\"2026-10-01T08:00:00Z\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches/" + id + "/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectedRolls.MISSING", contains(2)))
                .andExpect(jsonPath("$.rejectedRolls.OUT_OF_TOLERANCE", contains(1)));
    }

    @Test
    void sameRequestIdDifferentParamsIs409() throws Exception {
        String id = createBatch("B-" + UUID.randomUUID(), 1);
        String body1 = "{\"requestId\":\"m-1\",\"rollNo\":1,\"type\":\"ORIGINAL\","
                + "\"l\":51,\"a\":1,\"b\":1,\"measuredAt\":\"2026-10-01T08:00:00Z\"}";
        String body2 = "{\"requestId\":\"m-1\",\"rollNo\":1,\"type\":\"ORIGINAL\","
                + "\"l\":52,\"a\":1,\"b\":1,\"measuredAt\":\"2026-10-01T08:00:00Z\"}";
        mvc.perform(post("/api/batches/" + id + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches/" + id + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches/" + id + "/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict());
    }
}