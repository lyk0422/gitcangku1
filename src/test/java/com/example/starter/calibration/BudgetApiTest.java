package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不确定度预算接口测试：分量批量修改主流程（RSS 合成、四位小数）、
 * 名称重复/数值非法 422、版本过期 410、已放行固化 409、
 * 预算明细与诊断（实际值/要求值/差额）、修订历史、幂等重放与异参冲突。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BudgetApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM budget_revision_component");
        jdbc.update("DELETE FROM budget_revision");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM instrument_threshold");
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

    private void submit(String key, String instrument, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, by)))
                .andExpect(status().isCreated());
    }

    private void setThreshold(String instrument, String requestId, String threshold) throws Exception {
        mvc.perform(put("/api/instruments/{id}/threshold", instrument)
                        .header("X-Actor-Id", "setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"threshold\":\"" + threshold + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateComponentsComputesRssAndReturnsBudget() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "th-1", "1");
        submit("B-1", "INS-1", "alice");

        // 0.3 与 0.4 → sqrt(0.09+0.16)=0.5，四位小数 0.5000；分量按名称升序
        mvc.perform(put("/api/measurements/{key}/components", "B-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-1","expectedVersion":1,"components":[
                                  {"name":"repeatability","value":"0.3"},
                                  {"name":"reference","value":"0.4"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementKey").value("B-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.formula").value("RSS-4DP-HALF_UP"))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.components[0].name").value("reference"))
                .andExpect(jsonPath("$.components[0].value").value("0.4"))
                .andExpect(jsonPath("$.components[1].name").value("repeatability"))
                .andExpect(jsonPath("$.components[1].value").value("0.3"))
                .andExpect(jsonPath("$.threshold.threshold").value("1"))
                .andExpect(jsonPath("$.threshold.version").value(1))
                .andExpect(jsonPath("$.margin").value("0.5"))
                .andExpect(jsonPath("$.thresholdSatisfied").value(true))
                .andExpect(jsonPath("$.blockingReasons.length()").value(0));

        // 明细查询一致；测量版本已变为 2
        mvc.perform(get("/api/measurements/{key}/budget", "B-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"));
        mvc.perform(get("/api/measurements/{key}", "B-1"))
                .andExpect(jsonPath("$.version").value(2));

        // 修订历史固化一条 v2，含分量快照
        mvc.perform(get("/api/measurements/{key}/budget/history", "B-1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$[0].formula").value("RSS-4DP-HALF_UP"))
                .andExpect(jsonPath("$[0].changedBy").value("bob"))
                .andExpect(jsonPath("$[0].requestId").value("c-1"))
                .andExpect(jsonPath("$[0].components.length()").value(2))
                .andExpect(jsonPath("$[0].components[0].name").value("reference"));
    }

    @Test
    void combinedUncertaintyRoundsToFourDecimalsHalfUp() throws Exception {
        createCert("INS-1");
        setThreshold("INS-1", "th-1", "1");
        submit("B-2", "INS-1", "alice");
        // sqrt(0.1²+0.1²)=0.14142135… → 0.1414
        mvc.perform(put("/api/measurements/{key}/components", "B-2")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-2","expectedVersion":1,"components":[
                                  {"name":"a","value":"0.1"},{"name":"b","value":"0.1"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedUncertainty").value("0.1414"));
    }

    @Test
    void duplicateComponentNameReturns422() throws Exception {
        createCert("INS-1");
        submit("B-3", "INS-1", "alice");
        mvc.perform(put("/api/measurements/{key}/components", "B-3")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-3","expectedVersion":1,"components":[
                                  {"name":"a","value":"0.1"},{"name":"a","value":"0.2"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_COMPONENT_NAME"));
        // 校验失败不产生任何分量与版本变化
        mvc.perform(get("/api/measurements/{key}/budget", "B-3"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.components.length()").value(0));
    }

    @Test
    void invalidComponentValuesReturn422() throws Exception {
        createCert("INS-1");
        submit("B-4", "INS-1", "alice");
        String[] badValues = {"-0.1", "0.1234567", "abc", ""};
        for (int i = 0; i < badValues.length; i++) {
            mvc.perform(put("/api/measurements/{key}/components", "B-4")
                            .header("X-Actor-Id", "bob")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"c-4-%d","expectedVersion":1,"components":[
                                      {"name":"a","value":"%s"}]}
                                    """.formatted(i, badValues[i])))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_COMPONENT_VALUE"));
        }
        // 分量名为空白 → 400
        mvc.perform(put("/api/measurements/{key}/components", "B-4")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-4-x","expectedVersion":1,"components":[
                                  {"name":" ","value":"0.1"}]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staleVersionReturns410WithActualAndRequired() throws Exception {
        createCert("INS-1");
        submit("B-5", "INS-1", "alice");
        mvc.perform(put("/api/measurements/{key}/components", "B-5")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-5","expectedVersion":1,"components":[
                                  {"name":"a","value":"0.1"}]}
                                """))
                .andExpect(status().isOk());
        // 旧版本过期 → 410，消息含要求版本与实际版本
        mvc.perform(put("/api/measurements/{key}/components", "B-5")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-5b","expectedVersion":1,"components":[
                                  {"name":"a","value":"0.2"}]}
                                """))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VERSION_EXPIRED"))
                .andExpect(jsonPath("$.message", containsString("要求版本=1")))
                .andExpect(jsonPath("$.message", containsString("实际版本=2")));
        // 410 不改变当前分量
        mvc.perform(get("/api/measurements/{key}/budget", "B-5"))
                .andExpect(jsonPath("$.components[0].value").value("0.1"));
    }

    @Test
    void idempotentReplayReturnsFirstSnapshotWithoutSideEffects() throws Exception {
        createCert("INS-1");
        submit("B-6", "INS-1", "alice");
        String body = """
                {"requestId":"c-6","expectedVersion":1,"components":[
                  {"name":"a","value":"0.3"},{"name":"b","value":"0.4"}]}
                """;
        String first = mvc.perform(put("/api/measurements/{key}/components", "B-6")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 同参重放：返回首次完整快照，不产生新版本/新修订
        mvc.perform(put("/api/measurements/{key}/components", "B-6")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json(first));
        mvc.perform(get("/api/measurements/{key}/budget", "B-6"))
                .andExpect(jsonPath("$.version").value(2));
        Integer revisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_revision", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, revisions);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        createCert("INS-1");
        submit("B-7", "INS-1", "alice");
        mvc.perform(put("/api/measurements/{key}/components", "B-7")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-7","expectedVersion":1,"components":[
                                  {"name":"a","value":"0.1"}]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(put("/api/measurements/{key}/components", "B-7")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-7","expectedVersion":1,"components":[
                                  {"name":"a","value":"0.2"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void failedRequestDoesNotConsumeRequestId() throws Exception {
        createCert("INS-1");
        submit("B-8", "INS-1", "alice");
        // 失败（422）不占键
        mvc.perform(put("/api/measurements/{key}/components", "B-8")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-8","expectedVersion":1,"components":[
                                  {"name":"a","value":"-1"}]}
                                """))
                .andExpect(status().isUnprocessableEntity());
        // 同一 requestId 修正参数后可成功
        mvc.perform(put("/api/measurements/{key}/components", "B-8")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-8","expectedVersion":1,"components":[
                                  {"name":"a","value":"1"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void budgetDiagnosticsReportActualRequiredAndMargin() throws Exception {
        createCert("INS-1");
        submit("B-9", "INS-1", "alice");
        // 未登记阈值 → 诊断 THRESHOLD_MISSING，threshold/margin 为 null
        mvc.perform(get("/api/measurements/{key}/budget", "B-9"))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.0000"))
                .andExpect(jsonPath("$.threshold").doesNotExist())
                .andExpect(jsonPath("$.margin").doesNotExist())
                .andExpect(jsonPath("$.thresholdSatisfied").value(false))
                .andExpect(jsonPath("$.blockingReasons[0]").value("THRESHOLD_MISSING"));

        // 阈值 0.4，分量 0.3/0.4 → 合成 0.5 超限：实际值 0.5000、要求值 0.4、差额 -0.1
        setThreshold("INS-1", "th-9", "0.4");
        mvc.perform(put("/api/measurements/{key}/components", "B-9")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-9","expectedVersion":1,"components":[
                                  {"name":"a","value":"0.3"},{"name":"b","value":"0.4"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.threshold.threshold").value("0.4"))
                .andExpect(jsonPath("$.margin").value("-0.1"))
                .andExpect(jsonPath("$.thresholdSatisfied").value(false))
                .andExpect(jsonPath("$.blockingReasons[0]").value("UNCERTAINTY_EXCEEDED"));
    }

    @Test
    void emptyComponentListClearsBudget() throws Exception {
        createCert("INS-1");
        submit("B-10", "INS-1", "alice");
        mvc.perform(put("/api/measurements/{key}/components", "B-10")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-10","expectedVersion":1,"components":[
                                  {"name":"a","value":"0.3"}]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(put("/api/measurements/{key}/components", "B-10")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-10b","expectedVersion":2,"components":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.0000"))
                .andExpect(jsonPath("$.components.length()").value(0));
        // 历史两条，按版本升序
        mvc.perform(get("/api/measurements/{key}/budget/history", "B-10"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(3))
                .andExpect(jsonPath("$[1].components.length()").value(0));
    }

    @Test
    void unknownMeasurementReturns404() throws Exception {
        mvc.perform(get("/api/measurements/{key}/budget", "NO-SUCH"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/measurements/{key}/budget/history", "NO-SUCH"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/measurements/{key}/components", "NO-SUCH")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-x","expectedVersion":1,"components":[]}
                                """))
                .andExpect(status().isNotFound());
    }
}
