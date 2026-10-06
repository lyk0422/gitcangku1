package com.example.starter.observation.geo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 一致性确认 API 测试（真实 H2 内存库，MySQL 兼容模式，可控固定时钟）。
 * 覆盖合成样例边界、失败分支（422/400/409/404）、确认记录冻结与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeoConfirmationApiTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-02T03:04:05Z");

    /**
     * 固定时钟：确认记录的 confirmedAtUtc 可断言，替代系统时钟。
     */
    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM consistency_confirmation");
        jdbcTemplate.update("DELETE FROM geo_observation_version");
        jdbcTemplate.update("DELETE FROM geo_observation_current");
    }

    private void createGeo(String requestId, String observationId, String x, String y,
                           String a, String b, String c) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("x", x);
        body.put("y", y);
        body.put("a", a);
        body.put("b", b);
        body.put("c", c);
        body.put("operator", "op-seed");
        mockMvc.perform(post("/api/geo-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private ResultActions confirm(String requestId, String idA, int versionA, String idB, int versionB,
                                  String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationIdA", idA);
        body.put("expectedVersionA", versionA);
        body.put("observationIdB", idB);
        body.put("expectedVersionB", versionB);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/geo-observations/confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int confirmationCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consistency_confirmation", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 合成样例与边界 ----------

    @Test
    void confirmSyntheticSampleAccepted() throws Exception {
        // 两矩阵均单位阵，坐标差 (4, 0)：lhs = 2*16 = 32 <= 36 = 9*(2*2)，可确认
        createGeo("req-s1", "geo-a", "0", "0", "1", "0", "1");
        createGeo("req-s2", "geo-b", "4", "0", "1", "0", "1");

        confirm("req-s3", "geo-a", 1, "geo-b", 1, "op-confirm")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmationId").isNotEmpty())
                .andExpect(jsonPath("$.observationIdA").value("geo-a"))
                .andExpect(jsonPath("$.versionA").value(1))
                .andExpect(jsonPath("$.observationIdB").value("geo-b"))
                .andExpect(jsonPath("$.versionB").value(1))
                .andExpect(jsonPath("$.u").value("-4"))
                .andExpect(jsonPath("$.v").value("0"))
                .andExpect(jsonPath("$.sumA").value("2"))
                .andExpect(jsonPath("$.sumB").value("0"))
                .andExpect(jsonPath("$.sumC").value("2"))
                .andExpect(jsonPath("$.lhs").value("32"))
                .andExpect(jsonPath("$.rhs").value("36"))
                .andExpect(jsonPath("$.operator").value("op-confirm"))
                .andExpect(jsonPath("$.confirmedAtUtc").value("2026-01-02T03:04:05Z"));
        assertThat(confirmationCount()).isEqualTo(1);
        // 确认不改动源观测：两条观测仍为版本 1
        mockMvc.perform(get("/api/geo-observations/geo-a"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/geo-observations/geo-b"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void confirmBeyondThresholdReturns422() throws Exception {
        // 两矩阵均单位阵，坐标差 (4.243, 0)：lhs = 2*18.003049 = 36.006098 > 36，拒绝
        createGeo("req-t1", "geo-a", "0", "0", "1", "0", "1");
        createGeo("req-t2", "geo-b", "4.243", "0", "1", "0", "1");

        confirm("req-t3", "geo-a", 1, "geo-b", 1, "op-confirm")
                .andExpect(status().isUnprocessableEntity());
        assertThat(confirmationCount()).isZero();
    }

    @Test
    void confirmExactBoundaryAccepted() throws Exception {
        // 两矩阵均单位阵，坐标差 (3, 3)：lhs = 2*9 + 2*9 = 36 = rhs，恰好在门限上，可确认
        createGeo("req-b1", "geo-a", "0", "0", "1", "0", "1");
        createGeo("req-b2", "geo-b", "3", "3", "1", "0", "1");

        confirm("req-b3", "geo-a", 1, "geo-b", 1, "op-confirm")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lhs").value("36"))
                .andExpect(jsonPath("$.rhs").value("36"));
        assertThat(confirmationCount()).isEqualTo(1);
    }

    // ---------- 失败分支 ----------

    @Test
    void confirmWithStaleVersionReturns409() throws Exception {
        createGeo("req-v1", "geo-a", "0", "0", "1", "0", "1");
        createGeo("req-v2", "geo-b", "4", "0", "1", "0", "1");

        confirm("req-v3", "geo-a", 2, "geo-b", 1, "op-confirm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        confirm("req-v4", "geo-a", 1, "geo-b", 9, "op-confirm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        assertThat(confirmationCount()).isZero();
    }

    @Test
    void confirmWithTombstoneReturns422() throws Exception {
        createGeo("req-d1", "geo-a", "0", "0", "1", "0", "1");
        createGeo("req-d2", "geo-b", "4", "0", "1", "0", "1");
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-d3");
        deleteBody.put("expectedVersion", 1);
        deleteBody.put("operator", "op-del");
        mockMvc.perform(post("/api/geo-observations/geo-b/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());

        // 墓碑观测不能从当前字段补齐旧版本参与确认：直接 422
        confirm("req-d4", "geo-a", 1, "geo-b", 2, "op-confirm")
                .andExpect(status().isUnprocessableEntity());
        assertThat(confirmationCount()).isZero();
    }

    @Test
    void confirmWithSameObservationReturns422() throws Exception {
        createGeo("req-e1", "geo-a", "0", "0", "1", "0", "1");
        confirm("req-e2", "geo-a", 1, "geo-a", 1, "op-confirm")
                .andExpect(status().isUnprocessableEntity());
        assertThat(confirmationCount()).isZero();
    }

    @Test
    void confirmWithMissingObservationReturns404() throws Exception {
        createGeo("req-n1", "geo-a", "0", "0", "1", "0", "1");
        confirm("req-n2", "geo-a", 1, "geo-x", 1, "op-confirm")
                .andExpect(status().isNotFound());
        confirm("req-n3", "geo-x", 1, "geo-a", 1, "op-confirm")
                .andExpect(status().isNotFound());
        assertThat(confirmationCount()).isZero();
    }

    @Test
    void confirmWithBlankOperatorReturns400() throws Exception {
        createGeo("req-o1", "geo-a", "0", "0", "1", "0", "1");
        createGeo("req-o2", "geo-b", "4", "0", "1", "0", "1");
        confirm("req-o3", "geo-a", 1, "geo-b", 1, "")
                .andExpect(status().isBadRequest());
        assertThat(confirmationCount()).isZero();
    }

    // ---------- 确认记录冻结与历史 ----------

    @Test
    void confirmationFrozenAfterLaterChanges() throws Exception {
        createGeo("req-f1", "geo-a", "0", "0", "1", "0", "1");
        createGeo("req-f2", "geo-b", "4", "0", "1", "0", "1");
        String confirmationId = objectMapper.readTree(confirm("req-f3", "geo-a", 1, "geo-b", 1, "op-confirm")
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("confirmationId").asText();

        // 后续改测：geo-a 坐标与矩阵变更到版本 2
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-f4");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("x", "100");
        mergeBody.put("y", "100");
        mergeBody.put("a", "5");
        mergeBody.put("b", "1");
        mergeBody.put("c", "5");
        mergeBody.put("operator", "op-merge");
        mockMvc.perform(post("/api/geo-observations/geo-a/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 确认记录仍为冻结时的完整快照与比较值，后续改测不改历史
        mockMvc.perform(get("/api/geo-observations/confirmations/{id}", confirmationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionA").value(1))
                .andExpect(jsonPath("$.xA").value("0"))
                .andExpect(jsonPath("$.aA").value("1"))
                .andExpect(jsonPath("$.u").value("-4"))
                .andExpect(jsonPath("$.lhs").value("32"))
                .andExpect(jsonPath("$.rhs").value("36"))
                .andExpect(jsonPath("$.confirmedAtUtc").value("2026-01-02T03:04:05Z"));
        // 不存在的确认记录返回 404
        mockMvc.perform(get("/api/geo-observations/confirmations/{id}", "no-such-id"))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdAndParamsReplaysConfirmation() throws Exception {
        createGeo("req-r1", "geo-a", "0", "0", "1", "0", "1");
        createGeo("req-r2", "geo-b", "4", "0", "1", "0", "1");
        String first = confirm("req-r3", "geo-a", 1, "geo-b", 1, "op-confirm")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(first).get("confirmationId").asText();

        // 同键同参重放：返回首次完整结果（同一 confirmationId），不新增确认记录
        confirm("req-r3", "geo-a", 1, "geo-b", 1, "op-confirm")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmationId").value(firstId))
                .andExpect(jsonPath("$.lhs").value("32"));
        assertThat(confirmationCount()).isEqualTo(1);

        // 同键改参：409
        confirm("req-r3", "geo-b", 1, "geo-a", 1, "op-confirm")
                .andExpect(status().isConflict());
        confirm("req-r3", "geo-a", 1, "geo-b", 1, "op-other")
                .andExpect(status().isConflict());
        assertThat(confirmationCount()).isEqualTo(1);
    }

    @Test
    void failedConfirmationDoesNotOccupyRequestId() throws Exception {
        createGeo("req-k1", "geo-a", "0", "0", "1", "0", "1");
        createGeo("req-k2", "geo-b", "4.243", "0", "1", "0", "1");
        // 超出门限 422：失败不占键
        confirm("req-k3", "geo-a", 1, "geo-b", 1, "op-confirm")
                .andExpect(status().isUnprocessableEntity());
        // 修正数据后同一 requestId 可正常执行
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-k4");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("x", "4");
        mergeBody.put("y", "0");
        mergeBody.put("a", "1");
        mergeBody.put("b", "0");
        mergeBody.put("c", "1");
        mergeBody.put("operator", "op-merge");
        mockMvc.perform(post("/api/geo-observations/geo-b/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());
        confirm("req-k3", "geo-a", 1, "geo-b", 2, "op-confirm")
                .andExpect(status().isCreated());
        assertThat(confirmationCount()).isEqualTo(1);
    }
}
