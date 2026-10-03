package com.example.starter.kiln;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层合成调用测试：建程序、入炉、上传测温、放行、查询详情。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:kilntest_api;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestKilnConfig.class)
class KilnApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        KilnTestSupport.clean(jdbc);
    }

    @Test
    void syntheticFlowOverHttp() throws Exception {
        String programBody = """
                {"name":"STD-FIRING-HTTP","version":"v1","segments":[
                  {"startUtc":"2026-02-01T00:00:00Z","endUtc":"2026-02-01T01:00:00Z","targetTemp":800.0,"tolerance":20.0},
                  {"startUtc":"2026-02-01T01:00:00Z","endUtc":"2026-02-01T02:00:00Z","targetTemp":1100.0,"tolerance":25.0},
                  {"startUtc":"2026-02-01T02:00:00Z","endUtc":"2026-02-01T03:00:00Z","targetTemp":950.0,"tolerance":15.0}]}
                """;
        MvcResult programResult = mvc.perform(post("/api/programs")
                        .contentType(MediaType.APPLICATION_JSON).content(programBody))
                .andExpect(status().isCreated())
                .andReturn();
        long programId = objectMapper.readTree(programResult.getResponse().getContentAsString())
                .get("programId").asLong();

        MvcResult batchResult = mvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"HTTP-B1\",\"programId\":" + programId + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        long batchId = objectMapper.readTree(batchResult.getResponse().getContentAsString())
                .get("batchId").asLong();

        // 合成 5 分钟采样，覆盖 00:00 - 03:00 UTC
        StringJoiner points = new StringJoiner(",");
        Instant base = Instant.parse("2026-02-01T00:00:00Z");
        for (Instant t = base; !t.isAfter(base.plus(3, ChronoUnit.HOURS)); t = t.plus(5, ChronoUnit.MINUTES)) {
            double target = t.isBefore(base.plus(1, ChronoUnit.HOURS)) ? 800.0
                    : t.isBefore(base.plus(2, ChronoUnit.HOURS)) ? 1100.0 : 950.0;
            points.add("{\"measuredAt\":\"" + t + "\",\"temperature\":" + target + "}");
        }
        String uploadBody = "{\"requestId\":\"req-http-1\",\"points\":[" + points + "]}";
        mvc.perform(post("/api/batches/{id}/points", batchId)
                        .contentType(MediaType.APPLICATION_JSON).content(uploadBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inserted").value(37))
                .andExpect(jsonPath("$.replayed").value(false));

        // 同参重放幂等
        mvc.perform(post("/api/batches/{id}/points", batchId)
                        .contentType(MediaType.APPLICATION_JSON).content(uploadBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 异参 409
        mvc.perform(post("/api/batches/{id}/points", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-http-1\",\"points\":["
                                + "{\"measuredAt\":\"2026-02-01T00:00:00Z\",\"temperature\":1.0}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        mvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"RELEASE\",\"inspector\":\"qc-http\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("RELEASED"))
                .andExpect(jsonPath("$.pointsLimit").value(37))
                .andExpect(jsonPath("$.programVersion").value("v1"));

        MvcResult detailResult = mvc.perform(get("/api/batches/{id}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.segments.length()").value(3))
                .andReturn();
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsString());
        assertEquals("v1", detail.get("programVersion").asText());
        assertEquals("qc-http", detail.get("release").get("inspector").asText());
        assertTrue(detail.get("segments").get(0).get("worstDeviation").isDouble());
    }

    @Test
    void gapRejectedOverHttp() throws Exception {
        long programId = createProgramViaService();
        MvcResult batchResult = mvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"HTTP-GAP\",\"programId\":" + programId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long batchId = objectMapper.readTree(batchResult.getResponse().getContentAsString())
                .get("batchId").asLong();

        StringJoiner points = new StringJoiner(",");
        Instant base = Instant.parse("2026-02-01T00:00:00Z");
        for (Instant t = base; !t.isAfter(base.plus(3, ChronoUnit.HOURS)); t = t.plus(5, ChronoUnit.MINUTES)) {
            if (t.equals(base.plus(60, ChronoUnit.MINUTES))) {
                continue;
            }
            double target = t.isBefore(base.plus(1, ChronoUnit.HOURS)) ? 800.0
                    : t.isBefore(base.plus(2, ChronoUnit.HOURS)) ? 1100.0 : 950.0;
            points.add("{\"measuredAt\":\"" + t + "\",\"temperature\":" + target + "}");
        }
        mvc.perform(post("/api/batches/{id}/points", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-gap\",\"points\":[" + points + "]}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/batches/{id}/release", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"RELEASE\",\"inspector\":\"qc-http\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELEASE_CHECK_FAILED"))
                .andExpect(jsonPath("$.details[0].type").value("SAMPLING_GAP"))
                .andExpect(jsonPath("$.details[0].segmentSeq").value(1));
    }

    private long createProgramViaService() throws Exception {
        String programBody = """
                {"name":"STD-FIRING-GAP","version":"v1","segments":[
                  {"startUtc":"2026-02-01T00:00:00Z","endUtc":"2026-02-01T01:00:00Z","targetTemp":800.0,"tolerance":20.0},
                  {"startUtc":"2026-02-01T01:00:00Z","endUtc":"2026-02-01T02:00:00Z","targetTemp":1100.0,"tolerance":25.0},
                  {"startUtc":"2026-02-01T02:00:00Z","endUtc":"2026-02-01T03:00:00Z","targetTemp":950.0,"tolerance":15.0}]}
                """;
        MvcResult result = mvc.perform(post("/api/programs")
                        .contentType(MediaType.APPLICATION_JSON).content(programBody))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("programId").asLong();
    }
}