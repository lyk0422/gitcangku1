package com.example.starter.calibration;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 放行不确定度预算/阈值 H2 集成测试：
 * 预算缺失 422、阈值缺失 422、超阈值 422、边界（合成恰好等于阈值放行）、
 * 多对象整批原子失败不留部分结果、放行快照固化（后续阈值/预算修订不改写）、
 * 放行幂等同参重放/异参 409/失败不占键。
 */
class ReleaseUncertaintyApiTest extends AbstractCalibrationApiTest {

    private void ready(String key, String instrument, String threshold, String... pairs)
            throws Exception {
        createCertificate(instrument, "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0");
        createOpenThreshold(instrument, "2026-01-01T00:00:00Z", threshold);
        submitMeasurement(key, instrument, "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        registerBudget(key, "alice", budgetJson(pairs), null).andExpect(status().isOk());
    }

    @Test
    void releaseFailsWhenBudgetMissing() throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0");
        createOpenThreshold("INS-1", "2026-01-01T00:00:00Z", "1");
        submitMeasurement("N-1", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        release("carol", null, "{\"keys\":[\"N-1\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_UNPROCESSABLE"))
                .andExpect(jsonPath("$.failures[0].key").value("N-1"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("BUDGET_NOT_REGISTERED"));
        mvc.perform(get("/api/measurements/{key}", "N-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void releaseFailsWhenThresholdMissingAtMeasuredInstant() throws Exception {
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0");
        // 阈值只覆盖 2027 年，测量在 2026 年 → 缺失
        createThreshold("INS-1", "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1");
        submitMeasurement("N-2", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        registerBudget("N-2", "alice", budgetJson("u", "0.1"), null).andExpect(status().isOk());
        release("carol", null, "{\"keys\":[\"N-2\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("THRESHOLD_NOT_FOUND"));
    }

    @Test
    void combinedExactlyAtThresholdPassesAndAboveFails() throws Exception {
        // sqrt(0.3^2+0.4^2) = 0.5000
        ready("N-3", "INS-1", "0.5", "u_a", "0.3", "u_b", "0.4");
        release("carol", null, "{\"keys\":[\"N-3\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.items[0].thresholdValue").value("0.5"))
                .andExpect(jsonPath("$.items[0].calculationMethod").value("RSS_HALF_UP_4"));

        // 阈值 0.4999：0.5000 > 0.4999 → 超阈值
        createCertificate("INS-2", "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0");
        createOpenThreshold("INS-2", "2026-01-01T00:00:00Z", "0.4999");
        submitMeasurement("N-4", "INS-2", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        registerBudget("N-4", "alice", budgetJson("u_a", "0.3", "u_b", "0.4"), null)
                .andExpect(status().isOk());
        release("carol", null, "{\"keys\":[\"N-4\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("UNCERTAINTY_EXCEEDS_THRESHOLD"));
    }

    @Test
    void batchWithOneExceedingThresholdRejectsAllAtomically() throws Exception {
        ready("N-5", "INS-1", "1", "u", "0.1");
        ready("N-6", "INS-2", "0.01", "u", "0.9"); // 超阈值
        release("carol", null, "{\"keys\":[\"N-5\",\"N-6\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[?(@.key=='N-6')].reasons[0]")
                        .value("UNCERTAINTY_EXCEEDS_THRESHOLD"))
                .andExpect(jsonPath("$.failures.length()").value(1));
        // 整批回滚：N-5 也不得放行，无任何放行历史
        mvc.perform(get("/api/measurements/{key}", "N-5"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer releaseRows = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseRows, "整批失败不得留下任何放行记录");
    }

    @Test
    void releaseSnapshotNotRewrittenByLaterThresholdOrBudgetRevision() throws Exception {
        long thresholdV1 = createOpenThreshold("INS-1", "2026-01-01T00:00:00Z", "1");
        createCertificate("INS-1", "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0");
        submitMeasurement("N-7", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        registerBudget("N-7", "alice", budgetJson("u_a", "0.3", "u_b", "0.4"), null)
                .andExpect(status().isOk());

        String body = release("carol", null, "{\"keys\":[\"N-7\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].thresholdVersionId").value((int) thresholdV1))
                .andExpect(jsonPath("$.items[0].budgetVersion").value(1))
                .andExpect(jsonPath("$.items[0].combinedUncertainty").value("0.5000"))
                .andReturn().getResponse().getContentAsString();
        String firstBatchId = ((String) com.jayway.jsonpath.JsonPath.read(body, "$.batchId"));

        // 后续：阈值修订为更严的 0.01，预算替换为更大的分量
        createOpenThreshold("INS-1", "2027-06-01T00:00:00Z", "0.01");
        registerBudget("N-7", "alice", budgetJson("u_a", "9"), 1L).andExpect(status().isOk());

        // 旧放行快照不变
        mvc.perform(get("/api/measurements/{key}", "N-7"))
                .andExpect(jsonPath("$.releases.length()").value(1))
                .andExpect(jsonPath("$.releases[0].batchId").value(firstBatchId))
                .andExpect(jsonPath("$.releases[0].budgetVersion").value(1))
                .andExpect(jsonPath("$.releases[0].combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.releases[0].thresholdVersionId").value((int) thresholdV1))
                .andExpect(jsonPath("$.releases[0].thresholdValue").value("1"))
                .andExpect(jsonPath("$.releases[0].calculationMethod").value("RSS_HALF_UP_4"))
                // 当前预算已是新版本，但状态/历史不被回写
                .andExpect(jsonPath("$.budget.budgetVersion").value(2))
                .andExpect(jsonPath("$.budget.combinedUncertainty").value("9.0000"))
                .andExpect(jsonPath("$.status").value("RELEASED"));

        // 快照在数据库中实际固化
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                        + "WHERE m.measurement_key = 'N-7'");
        assertEquals(thresholdV1, ((Number) row.get("threshold_version_id")).longValue());
        assertEquals(0, new java.math.BigDecimal(row.get("threshold_value").toString())
                .compareTo(new java.math.BigDecimal("1.000000")));
        assertEquals(0, new java.math.BigDecimal(row.get("combined_uncertainty").toString())
                .compareTo(new java.math.BigDecimal("0.5000")));
    }

    @Test
    void releaseIdempotencyReplayConflictAndFailureDoesNotOccupyKey() throws Exception {
        ready("N-8", "INS-1", "1", "u", "0.1");
        ready("N-9", "INS-2", "1", "u", "0.2");
        // 首次成功
        String first = release("carol", "REL-1", "{\"keys\":[\"N-8\"]}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstBatchId = ((String) com.jayway.jsonpath.JsonPath.read(first, "$.batchId"));
        // 同参重放：返回首次完整响应快照（同一 batchId），不新增放行历史
        String replay = release("carol", "REL-1", "{\"keys\":[\"N-8\"]}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(firstBatchId, com.jayway.jsonpath.JsonPath.read(replay, "$.batchId"));
        Integer releaseRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                        + "WHERE m.measurement_key = 'N-8'", Integer.class);
        assertEquals(1, releaseRows, "重放不得新增放行记录");

        // 异参（不同键集合）→ 409
        release("carol", "REL-1", "{\"keys\":[\"N-9\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_CONFLICT"));

        // 失败不占键：REL-2 先用于失败批次（N-9 已 PENDING 但集合含未知键），再用于成功批次
        release("carol", "REL-2", "{\"keys\":[\"N-9\",\"MISSING-X\"]}")
                .andExpect(status().isConflict());
        Integer idemAfterFailure = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'REL-2'", Integer.class);
        assertEquals(0, idemAfterFailure, "失败请求不得占用 requestId");
        release("carol", "REL-2", "{\"keys\":[\"N-9\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").isString());
    }
}
