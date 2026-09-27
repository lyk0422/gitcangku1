package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不确定度预算接口测试：阈值版本管理、分量批量修改（平方和开根 4 位小数）、
 * 422/410 失败分支、批量原子性、requestId 幂等、放行联合判定与快照固化、诊断与历史查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UncertaintyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM uncertainty_budget_revision");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM uncertainty_threshold");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private void setThreshold(String instrument, String maxUncertainty) throws Exception {
        setThreshold(instrument, maxUncertainty, java.util.UUID.randomUUID().toString());
    }

    private void setThreshold(String instrument, String maxUncertainty, String requestId)
            throws Exception {
        mvc.perform(put("/api/instruments/{id}/uncertainty-threshold", instrument)
                        .header("X-Actor-Id", "metrology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId
                                + "\",\"maxUncertainty\":\"" + maxUncertainty + "\"}"))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"}
                                """.formatted(key, instrument)))
                .andExpect(status().isCreated());
    }

    private MvcResult putComponents(String key, int version, String requestId, String componentsJson)
            throws Exception {
        return mvc.perform(put("/api/measurements/{key}/uncertainty-components", key)
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","measurementVersion":%d,"components":%s}
                                """.formatted(requestId, version, componentsJson)))
                .andReturn();
    }

    @Test
    void setComponentsComputesRootSumOfSquaresWithFourDecimals() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-1", "INS-1");

        // sqrt(0.3² + 0.4²) = 0.5 → "0.5000"；第二次修订验证版本递增
        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-rss-1","measurementVersion":1,
                                 "components":[{"name":"comp-b","value":"0.4"},
                                               {"name":"comp-a","value":"0.3"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementKey").value("U-1"))
                .andExpect(jsonPath("$.measurementVersion").value(2))
                .andExpect(jsonPath("$.calcSpec").value("RSS-4DP-HALF_UP"))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.thresholdVersion").value(1))
                .andExpect(jsonPath("$.thresholdValue").value("1"))
                .andExpect(jsonPath("$.withinThreshold").value(true))
                .andExpect(jsonPath("$.components[0].name").value("comp-a"))
                .andExpect(jsonPath("$.components[0].value").value("0.3"))
                .andExpect(jsonPath("$.components[1].name").value("comp-b"))
                .andExpect(jsonPath("$.components[1].value").value("0.4"));

        // 明细查询与预算一致；测量版本同步递增
        mvc.perform(get("/api/measurements/{key}/uncertainty", "U-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementVersion").value(2))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.components.length()").value(2));
        mvc.perform(get("/api/measurements/{key}", "U-1"))
                .andExpect(jsonPath("$.measurementVersion").value(2));

        // 历史：一次修订，按测量版本升序，分量快照完整
        mvc.perform(get("/api/measurements/{key}/uncertainty/history", "U-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].measurementVersion").value(2))
                .andExpect(jsonPath("$[0].combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$[0].thresholdVersion").value(1))
                .andExpect(jsonPath("$[0].changedBy").value("bob"))
                .andExpect(jsonPath("$[0].requestId").value("req-rss-1"))
                .andExpect(jsonPath("$[0].components[0].name").value("comp-a"))
                .andExpect(jsonPath("$[0].components[1].name").value("comp-b"));
    }

    @Test
    void duplicateComponentNameReturns422() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-2", "INS-1");

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-2")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-dup","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"0.1"},
                                               {"name":"comp-a","value":"0.2"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_COMPONENT_NAME"));

        // 校验失败不产生任何预算变更
        mvc.perform(get("/api/measurements/{key}/uncertainty", "U-2"))
                .andExpect(jsonPath("$.measurementVersion").value(1))
                .andExpect(jsonPath("$.components.length()").value(0))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.0000"));
    }

    @Test
    void negativeOrMalformedComponentValueReturns422() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-3", "INS-1");

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-3")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-neg","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"-0.1"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COMPONENT_VALUE"));

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-3")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-bad","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"abc"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COMPONENT_VALUE"));

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-3")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-blank","measurementVersion":1,
                                 "components":[{"name":"  ","value":"0.1"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COMPONENT_NAME"));
    }

    @Test
    void missingThresholdReturns422OnComponentRegistration() throws Exception {
        createCert("INS-1");
        submit("U-4", "INS-1");

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-4")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-no-th","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"0.1"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("THRESHOLD_MISSING"));

        // 未设置阈值时明细查询阈值相关字段为 null
        mvc.perform(get("/api/measurements/{key}/uncertainty", "U-4"))
                .andExpect(jsonPath("$.thresholdVersion").doesNotExist())
                .andExpect(jsonPath("$.thresholdValue").doesNotExist())
                .andExpect(jsonPath("$.withinThreshold").doesNotExist());
    }

    @Test
    void staleMeasurementVersionReturns410() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-5", "INS-1");

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-5")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-v1","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"0.1"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementVersion").value(2));

        // 旧版本再修改 → 410
        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-5")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-v1-again","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"0.2"}]}"""))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("MEASUREMENT_VERSION_STALE"));

        // 预算保持第一次修订结果
        mvc.perform(get("/api/measurements/{key}/uncertainty", "U-5"))
                .andExpect(jsonPath("$.measurementVersion").value(2))
                .andExpect(jsonPath("$.components[0].value").value("0.1"));
    }

    @Test
    void invalidBatchDoesNotChangeExistingBudget() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-6", "INS-1");

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-6")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-ok","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"0.3"}]}"""))
                .andExpect(status().isOk());

        // 批量修改含一个非法分量 → 整体 422，旧预算不变
        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-6")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-partial","measurementVersion":2,
                                 "components":[{"name":"comp-a","value":"0.9"},
                                               {"name":"comp-b","value":"-1"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COMPONENT_VALUE"));

        mvc.perform(get("/api/measurements/{key}/uncertainty", "U-6"))
                .andExpect(jsonPath("$.measurementVersion").value(2))
                .andExpect(jsonPath("$.components.length()").value(1))
                .andExpect(jsonPath("$.components[0].name").value("comp-a"))
                .andExpect(jsonPath("$.components[0].value").value("0.3"))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.3000"));
        Integer revisionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_budget_revision", Integer.class);
        assertEquals(1, revisionCount);
    }

    @Test
    void idempotentReplayReturnsFirstSnapshotWithoutSideEffects() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-7", "INS-1");

        MvcResult first = putComponents("U-7", 1, "req-replay",
                "[{\"name\":\"comp-a\",\"value\":\"0.3\"}]");
        assertEquals(200, first.getResponse().getStatus());
        String firstBody = first.getResponse().getContentAsString();

        // 同参重放：返回首次完整响应快照，不产生新修订
        MvcResult replay = putComponents("U-7", 1, "req-replay",
                "[{\"name\":\"comp-a\",\"value\":\"0.3\"}]");
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(firstBody, replay.getResponse().getContentAsString());

        Integer revisionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_budget_revision", Integer.class);
        assertEquals(1, revisionCount);
        mvc.perform(get("/api/measurements/{key}/uncertainty", "U-7"))
                .andExpect(jsonPath("$.measurementVersion").value(2));
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-8", "INS-1");

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-8")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-conflict","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"0.3"}]}"""))
                .andExpect(status().isOk());

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-8")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-conflict","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"0.9"}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-9", "INS-1");

        // 第一次使用 req-fail 失败（非法数值）→ 不占键
        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-9")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-fail","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"-0.3"}]}"""))
                .andExpect(status().isUnprocessableEntity());

        // 同一 requestId 修正参数后可成功
        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-9")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-fail","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"0.3"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedUncertainty").value("0.3000"));
    }

    @Test
    void releaseBlockedWhenUncertaintyExceedsThreshold() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "0.4");
        submit("U-10", "INS-1");
        putComponents("U-10", 1, "req-exceed",
                "[{\"name\":\"comp-a\",\"value\":\"0.3\"},{\"name\":\"comp-b\",\"value\":\"0.4\"}]");

        // 合成不确定度 0.5000 > 阈值 0.4 → 整批 409，测量保持待放行
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-10\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].key").value("U-10"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("UNCERTAINTY_EXCEEDED"));

        mvc.perform(get("/api/measurements/{key}", "U-10"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount);
    }

    @Test
    void releaseBlockedWhenThresholdMissing() throws Exception {
        createCert("INS-1");
        submit("U-11", "INS-1");

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-11\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("THRESHOLD_MISSING"));

        mvc.perform(get("/api/measurements/{key}", "U-11"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void releaseSnapshotFrozenAgainstLaterThresholdRevision() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "0.6");
        submit("U-12", "INS-1");
        putComponents("U-12", 1, "req-snap",
                "[{\"name\":\"comp-a\",\"value\":\"0.3\"},{\"name\":\"comp-b\",\"value\":\"0.4\"}]");

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-12\"]}"))
                .andExpect(status().isOk());

        // 放行快照：合成不确定度、阈值版本与数值、预算版本、计算口径、分量全部固化
        mvc.perform(get("/api/measurements/{key}", "U-12"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releases[0].combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.releases[0].thresholdVersion").value(1))
                .andExpect(jsonPath("$.releases[0].thresholdValue").value("0.6"))
                .andExpect(jsonPath("$.releases[0].budgetVersion").value(2))
                .andExpect(jsonPath("$.releases[0].calcSpec").value("RSS-4DP-HALF_UP"))
                .andExpect(jsonPath("$.releases[0].components[0].name").value("comp-a"))
                .andExpect(jsonPath("$.releases[0].components[1].name").value("comp-b"));

        // 后续阈值修订（收紧到 0.1）不改写旧放行快照
        setThreshold("INS-1", "0.1");
        mvc.perform(get("/api/measurements/{key}", "U-12"))
                .andExpect(jsonPath("$.releases[0].combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.releases[0].thresholdVersion").value(1))
                .andExpect(jsonPath("$.releases[0].thresholdValue").value("0.6"));

        // 阈值历史保留两个版本，当前阈值为新版本
        mvc.perform(get("/api/instruments/{id}/uncertainty-threshold/history", "INS-1"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].maxUncertainty").value("0.6"))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].maxUncertainty").value("0.1"));
        mvc.perform(get("/api/instruments/{id}/uncertainty-threshold", "INS-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.maxUncertainty").value("0.1"));
    }

    @Test
    void modifyingComponentsAfterReleaseReturns409() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-13", "INS-1");
        putComponents("U-13", 1, "req-before-release", "[{\"name\":\"comp-a\",\"value\":\"0.3\"}]");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-13\"]}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-13")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-after-release","measurementVersion":2,
                                 "components":[{"name":"comp-a","value":"0.1"}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEASUREMENT_RELEASED"));
    }

    @Test
    void diagnosticsReturnsActualRequiredMarginAndContributions() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "0.7");
        submit("U-14", "INS-1");
        putComponents("U-14", 1, "req-diag",
                "[{\"name\":\"comp-a\",\"value\":\"0.3\"},{\"name\":\"comp-b\",\"value\":\"0.4\"}]");

        mvc.perform(get("/api/measurements/{key}/uncertainty/diagnostics", "U-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementKey").value("U-14"))
                .andExpect(jsonPath("$.calcSpec").value("RSS-4DP-HALF_UP"))
                .andExpect(jsonPath("$.componentCount").value(2))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.thresholdVersion").value(1))
                .andExpect(jsonPath("$.thresholdValue").value("0.7"))
                .andExpect(jsonPath("$.margin").value("0.2"))
                .andExpect(jsonPath("$.withinThreshold").value(true))
                .andExpect(jsonPath("$.contributions[0].name").value("comp-a"))
                .andExpect(jsonPath("$.contributions[0].squared").value("0.09"))
                .andExpect(jsonPath("$.contributions[0].share").value("0.360000"))
                .andExpect(jsonPath("$.contributions[1].name").value("comp-b"))
                .andExpect(jsonPath("$.contributions[1].squared").value("0.16"))
                .andExpect(jsonPath("$.contributions[1].share").value("0.640000"));
    }

    @Test
    void emptyComponentsClearBudgetAndCombinedIsZero() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-15", "INS-1");
        putComponents("U-15", 1, "req-fill", "[{\"name\":\"comp-a\",\"value\":\"0.3\"}]");

        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "U-15")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-clear","measurementVersion":2,"components":[]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementVersion").value(3))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.0000"))
                .andExpect(jsonPath("$.components.length()").value(0))
                .andExpect(jsonPath("$.withinThreshold").value(true));
    }

    @Test
    void thresholdValidationAndIdempotency() throws Exception {
        // 非法阈值数值 → 422
        mvc.perform(put("/api/instruments/{id}/uncertainty-threshold", "INS-1")
                        .header("X-Actor-Id", "metrology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"th-bad\",\"maxUncertainty\":\"-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_THRESHOLD_VALUE"));

        // 同 requestId 同参重放 → 返回首次快照（版本不重复递增）
        setThreshold("INS-1", "0.5", "th-replay");
        mvc.perform(put("/api/instruments/{id}/uncertainty-threshold", "INS-1")
                        .header("X-Actor-Id", "metrology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"th-replay\",\"maxUncertainty\":\"0.5\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/instruments/{id}/uncertainty-threshold/history", "INS-1"))
                .andExpect(jsonPath("$.length()").value(1));

        // 同 requestId 异参 → 409
        mvc.perform(put("/api/instruments/{id}/uncertainty-threshold", "INS-1")
                        .header("X-Actor-Id", "metrology")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"th-replay\",\"maxUncertainty\":\"0.9\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 未设置阈值的仪器查询当前阈值 → 404
        mvc.perform(get("/api/instruments/{id}/uncertainty-threshold", "INS-OTHER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void releaseWithRequestIdReplaysSameSnapshot() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "1");
        submit("U-16", "INS-1");

        MvcResult first = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-16\"],\"requestId\":\"rel-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").isString())
                .andReturn();

        // 同参重放：返回首次响应快照（同一 batchId），不重复写放行历史
        String firstBatchId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.batchId");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-16\"],\"requestId\":\"rel-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(firstBatchId));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(1, releaseCount);

        // 同 requestId 异参 → 409
        submit("U-16B", "INS-1");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"U-16B\"],\"requestId\":\"rel-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void budgetQueriesUnknownMeasurementReturn404() throws Exception {
        mvc.perform(get("/api/measurements/{key}/uncertainty", "NO-SUCH"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/measurements/{key}/uncertainty/history", "NO-SUCH"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/measurements/{key}/uncertainty/diagnostics", "NO-SUCH"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/measurements/{key}/uncertainty-components", "NO-SUCH")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-404","measurementVersion":1,
                                 "components":[{"name":"comp-a","value":"0.1"}]}"""))
                .andExpect(status().isNotFound());
    }
}
