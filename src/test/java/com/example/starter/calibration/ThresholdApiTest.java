package com.example.starter.calibration;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阈值版本 H2 集成测试：创建、开放区间修订衔接、区间重叠 409、阈值为负 422、404；
 * 阈值按测量时刻（左闭右开）解析，后续修订不改写已固化的旧放行快照。
 */
class ThresholdApiTest extends AbstractCalibrationApiTest {

    @Test
    void createThresholdReturns201WithVersion() throws Exception {
        long id = createOpenThreshold("INS-T", "2026-01-01T00:00:00Z", "0.5");
        mvc.perform(get("/api/thresholds/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.instrumentId").value("INS-T"))
                .andExpect(jsonPath("$.maxUncertainty").value("0.5"))
                .andExpect(jsonPath("$.validTo").doesNotExist());
    }

    @Test
    void negativeThresholdIs422AndMalformedIs400() throws Exception {
        mvc.perform(post("/api/thresholds").contentType("application/json")
                        .content("""
                                {"instrumentId":"INS-T","validFrom":"2026-01-01T00:00:00Z",
                                 "maxUncertainty":"-0.01"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_NONNEGATIVE_VALUE"));
        mvc.perform(post("/api/thresholds").contentType("application/json")
                        .content("""
                                {"instrumentId":"INS-T","validFrom":"2026-01-01T00:00:00Z",
                                 "maxUncertainty":"nope"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void boundedOverlapRejectedButAdjacentAllowed() throws Exception {
        createThreshold("INS-T", "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z", "1");
        // 重叠
        mvc.perform(post("/api/thresholds").contentType("application/json")
                        .content("""
                                {"instrumentId":"INS-T","validFrom":"2026-03-01T00:00:00Z",
                                 "validTo":"2026-09-01T00:00:00Z","maxUncertainty":"1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THRESHOLD_OVERLAP"));
        // 相邻（右开端点相接）
        createThreshold("INS-T", "2026-06-01T00:00:00Z", "2027-01-01T00:00:00Z", "1");
        // 其他仪器不受影响
        createThreshold("INS-X", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "1");
        mvc.perform(get("/api/thresholds/{id}", 999999)).andExpect(status().isNotFound());
    }

    @Test
    void openEndedRevisionClosesPreviousAtNewStartAndResolvesByMeasuredInstant() throws Exception {
        // v1 开放区间 [2026-01-01, ∞) 阈值 1
        long v1 = createOpenThreshold("INS-T", "2026-01-01T00:00:00Z", "1");
        // v2 开放区间 [2027-01-01, ∞)：v1 被收敛为 [2026-01-01, 2027-01-01)
        long v2 = createOpenThreshold("INS-T", "2027-01-01T00:00:00Z", "0.2");

        mvc.perform(get("/api/thresholds/{id}", v1))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.validTo").value("2027-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.maxUncertainty").value("1"));
        mvc.perform(get("/api/thresholds/{id}", v2))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.validTo").doesNotExist())
                .andExpect(jsonPath("$.maxUncertainty").value("0.2"));

        // 左闭右开解析：2026 年测量走 v1，2027 年起走 v2，恰好 2027-01-01 走 v2
        createCertificate("INS-T", "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "1", "0");
        submitMeasurement("M-OLD", "INS-T", "2026-12-31T23:59:59Z", "1", "0", "9", "alice");
        submitMeasurement("M-BOUND", "INS-T", "2027-01-01T00:00:00Z", "1", "0", "9", "alice");
        submitMeasurement("M-NEW", "INS-T", "2027-06-01T00:00:00Z", "1", "0", "9", "alice");
        registerBudget("M-OLD", "alice", budgetJson("u", "0.9"), null).andExpect(status().isOk());
        registerBudget("M-BOUND", "alice", budgetJson("u", "0.9"), null).andExpect(status().isOk());
        registerBudget("M-NEW", "alice", budgetJson("u", "0.1"), null).andExpect(status().isOk());

        // 0.9 ≤ v1 的 1：放行成功；恰好边界时刻走 v2(0.2)：超阈值 422；新测量 0.1 ≤ 0.2：成功
        release("carol", null, "{\"keys\":[\"M-OLD\"]}").andExpect(status().isOk());
        release("carol", null, "{\"keys\":[\"M-BOUND\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("UNCERTAINTY_EXCEEDS_THRESHOLD"));
        release("carol", null, "{\"keys\":[\"M-NEW\"]}").andExpect(status().isOk());
    }

    @Test
    void newOpenEndedBeforeExistingStartRejected() throws Exception {
        createOpenThreshold("INS-T", "2027-01-01T00:00:00Z", "1");
        mvc.perform(post("/api/thresholds").contentType("application/json")
                        .content("""
                                {"instrumentId":"INS-T","validFrom":"2026-01-01T00:00:00Z",
                                 "maxUncertainty":"1"}"""))
                .andExpect(status().isConflict());
    }
}
