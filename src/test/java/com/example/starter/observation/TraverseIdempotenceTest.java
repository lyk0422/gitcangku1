package com.example.starter.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闭合导线成果生成的幂等与去重边界测试：同键同参重放、异参 409、失败不占键、
 * 生成时刻来自可控时钟（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraverseIdempotenceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-02-03T04:05:06Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM traverse_leg");
        jdbcTemplate.update("DELETE FROM traverse_result");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        when(clock.instant()).thenReturn(FIXED_INSTANT);
    }

    private void createObservation(String requestId, String observationId,
                                   String dx, String dy, String w) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点" + observationId);
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("dx", dx);
        body.put("dy", dy);
        body.put("w", w);
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void createSyntheticObservations() throws Exception {
        createObservation("req-s1", "obs-t1", "1", "0", "100");
        createObservation("req-s2", "obs-t2", "0", "1", "200");
        createObservation("req-s3", "obs-t3", "-0.94", "-1", "300");
    }

    private ResultActions generate(String requestId, List<Map<String, Object>> legs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "surveyor-a");
        body.put("legs", legs);
        return mockMvc.perform(post("/api/traverses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private List<Map<String, Object>> syntheticLegs() {
        return List.of(
                Map.of("observationId", "obs-t1", "version", 1),
                Map.of("observationId", "obs-t2", "version", 1),
                Map.of("observationId", "obs-t3", "version", 1));
    }

    @Test
    void sameKeySameParamsReplaysFirstResponse() throws Exception {
        createSyntheticObservations();

        MvcResult first = generate("req-i1", syntheticLegs())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.generatedAtUtc").value("2026-02-03T04:05:06Z"))
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();
        String traverseId = objectMapper.readTree(firstBody).get("traverseId").asText();

        // 同键同参重放：返回首次完整响应（同成果标识、同状态码）
        MvcResult replayed = generate("req-i1", syntheticLegs())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.traverseId").value(traverseId))
                .andReturn();
        assertThat(replayed.getResponse().getContentAsString()).isEqualTo(firstBody);

        // 去重记录与成果各占一行，未重复生成
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-i1'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_result", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_leg", Integer.class)).isEqualTo(3);
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        createSyntheticObservations();
        generate("req-i2", syntheticLegs()).andExpect(status().isCreated());

        // 同键异参：调整边顺序即视为不同参数（顺序有意义）
        List<Map<String, Object>> reordered = List.of(
                Map.of("observationId", "obs-t2", "version", 1),
                Map.of("observationId", "obs-t1", "version", 1),
                Map.of("observationId", "obs-t3", "version", 1));
        generate("req-i2", reordered).andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_result", Integer.class)).isEqualTo(1);
    }

    @Test
    void failedAttemptDoesNotOccupyKey() throws Exception {
        createSyntheticObservations();
        // 先制造一次越限失败：临时把 obs-t3 位移改成 (-0.899,-1) 使闭合差越限
        createObservation("req-s4", "obs-t4", "-0.899", "-1", "300");
        List<Map<String, Object>> exceeding = List.of(
                Map.of("observationId", "obs-t1", "version", 1),
                Map.of("observationId", "obs-t2", "version", 1),
                Map.of("observationId", "obs-t4", "version", 1));
        generate("req-i3", exceeding).andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-i3'", Integer.class)).isZero();

        // 同键换正确参数：失败不占键，可成功生成
        generate("req-i3", syntheticLegs())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.closureDx").value("0.06"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_result", Integer.class)).isEqualTo(1);
    }

    @Test
    void generatedAtUtcComesFromControllableClock() throws Exception {
        createSyntheticObservations();
        MvcResult result = generate("req-i4", syntheticLegs())
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("generatedAtUtc").asText()).isEqualTo("2026-02-03T04:05:06Z");

        // 落库时刻同样来自可控时钟
        java.sql.Timestamp stored = jdbcTemplate.queryForObject(
                "SELECT generated_at_utc FROM traverse_result WHERE traverse_id = ?",
                java.sql.Timestamp.class, body.get("traverseId").asText());
        assertThat(stored.toInstant()).isEqualTo(FIXED_INSTANT);
    }
}
