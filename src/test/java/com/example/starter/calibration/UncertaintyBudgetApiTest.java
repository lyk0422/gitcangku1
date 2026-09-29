package com.example.starter.calibration;

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

import com.example.starter.calibration.ControlledClockConfig.ControlledClock;
import com.jayway.jsonpath.JsonPath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 不确定度预算真实 H2 数据库测试：阈值版本化、RSS 预算登记/修订、四位小数口径、
 * 放行阈值闸口与快照固化、422/410/409 失败分支、完整后态校验与事务回滚、
 * requestId 幂等（同参重放/异参 409/失败不占键）、历史不可变与只读查询、受控 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControlledClockConfig.class)
class UncertaintyBudgetApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControlledClock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM uncertainty_component");
        jdbc.update("DELETE FROM uncertainty_budget");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM uncertainty_threshold");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        clock.setInstant(ControlledClockConfig.START);
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void publishThreshold(String instrument, String max, String actor) throws Exception {
        mvc.perform(post("/api/thresholds").header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","maxUncertainty":"%s"}
                                """.formatted(instrument, max)))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument, String reading, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, by)))
                .andExpect(status().isCreated());
    }

    private MvcResult saveBudget(String key, String actor, String expectedVersion,
                                 String... nameValueJsonPairs) throws Exception {
        String components = String.join(",", (CharSequence[]) nameValueJsonPairs);
        String body = "{\"components\":[" + components + "]"
                + (expectedVersion == null ? "" : ",\"expectedVersion\":" + expectedVersion) + "}";
        return mvc.perform(put("/api/measurements/{key}/budget", key)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MvcResult release(String actor, String... keys) throws Exception {
        String array = String.join(",", java.util.Arrays.stream(keys).map(k -> "\"" + k + "\"").toList());
        return mvc.perform(post("/api/measurements/release").header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[" + array + "]}"))
                .andReturn();
    }

    @Test
    void registerBudgetComputesRssAndFourDecimalDisplay() throws Exception {
        createCert("INS-1");
        publishThreshold("INS-1", "5.2", "admin");
        submit("U-1", "INS-1", "1", "alice");

        // sqrt(3² + 4²) = 5；actual=5.0000，required=5.2000，difference=0.2000
        mvc.perform(get("/api/measurements/{key}/budget", "U-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUDGET_MISSING"));

        saveBudget("U-1", "carol", null, "{\"name\":\"repeatability\",\"value\":\"3\"}",
                "{\"name\":\"reference\",\"value\":\"4\"}");
        mvc.perform(get("/api/measurements/{key}/budget", "U-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.calcMethod").value("RSS_V1"))
                .andExpect(jsonPath("$.combinedUncertainty").value("5.0000"))
                .andExpect(jsonPath("$.actual").value("5.0000"))
                .andExpect(jsonPath("$.required").value("5.2"))
                .andExpect(jsonPath("$.difference").value("0.2"))
                .andExpect(jsonPath("$.withinThreshold").value(true))
                .andExpect(jsonPath("$.thresholdVersion").value(1))
                .andExpect(jsonPath("$.components[0].name").value("repeatability"))
                .andExpect(jsonPath("$.components[0].ordinal").value(0))
                .andExpect(jsonPath("$.components[1].name").value("reference"))
                .andExpect(jsonPath("$.components[1].ordinal").value(1));

        // 测量明细挂接当前预算与版本号
        mvc.perform(get("/api/measurements/{key}", "U-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.budget.combinedUncertainty").value("5.0000"));
    }

    @Test
    void irrationalRssRoundsHalfUpToFourDecimals() throws Exception {
        createCert("INS-1");
        publishThreshold("INS-1", "2", "admin");
        submit("U-R", "INS-1", "1", "alice");
        // sqrt(1+1)=1.4142135... → 1.4142
        saveBudget("U-R", "carol", null, "{\"name\":\"a\",\"value\":\"1\"}",
                "{\"name\":\"b\",\"value\":\"1\"}");
        mvc.perform(get("/api/measurements/{key}/budget", "U-R"))
                .andExpect(jsonPath("$.combinedUncertainty").value("1.4142"));
    }

    @Test
    void invalidBudgetInputsReturn422AndPersistNothing() throws Exception {
        createCert("INS-1");
        publishThreshold("INS-1", "5", "admin");
        submit("U-2", "INS-1", "1", "alice");

        // 分量名称重复 → 422
        mvc.perform(put("/api/measurements/{key}/budget", "U-2").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"x","value":"1"},{"name":"x","value":"2"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_COMPONENT_NAME"));

        // 负值 → 422；格式非法（超过 6 位小数）→ 400
        mvc.perform(put("/api/measurements/{key}/budget", "U-2").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"x","value":"-0.000001"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        mvc.perform(put("/api/measurements/{key}/budget", "U-2").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"x","value":"1.0000001"}]}"""))
                .andExpect(status().isBadRequest());

        // 空预算 → 422
        mvc.perform(put("/api/measurements/{key}/budget", "U-2").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"components\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_BUDGET"));

        // 整批失败不留部分结果：无任何预算版本，测量仍指向空预算、版本未推进
        Integer budgetRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_budget b JOIN measurement m ON m.id = b.measurement_id "
                        + "WHERE m.measurement_key = 'U-2'", Integer.class);
        assertEquals(0, budgetRows);
        mvc.perform(get("/api/measurements/{key}", "U-2"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.budget").doesNotExist());
    }

    @Test
    void missingThresholdReturns422() throws Exception {
        createCert("INS-NOTR");
        submit("U-3", "INS-NOTR", "1", "alice");
        mvc.perform(put("/api/measurements/{key}/budget", "U-3").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"x","value":"1"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("THRESHOLD_MISSING"));

        mvc.perform(get("/api/thresholds/current").param("instrumentId", "INS-NOTR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("THRESHOLD_MISSING"));
    }

    @Test
    void staleExpectedVersionReturns410() throws Exception {
        createCert("INS-1");
        publishThreshold("INS-1", "5", "admin");
        submit("U-4", "INS-1", "1", "alice");

        saveBudget("U-4", "carol", "1", "{\"name\":\"a\",\"value\":\"1\"}");
        // 初始版本 1 已推进到 2；再带 expectedVersion=1 → 410
        mvc.perform(put("/api/measurements/{key}/budget", "U-4").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"a","value":"2"}],"expectedVersion":1}"""))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("VERSION_STALE"));
        // 带上当前版本 2 → 成功
        mvc.perform(put("/api/measurements/{key}/budget", "U-4").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"a","value":"2"}],"expectedVersion":2}"""))
                .andExpect(status().isOk());
    }

    @Test
    void revisionValidatesCompletePostStateBeforeCommit() throws Exception {
        createCert("INS-1");
        publishThreshold("INS-1", "5", "admin");
        submit("U-5", "INS-1", "1", "alice");
        // v1 预算 [3,4] → 5，合格
        saveBudget("U-5", "carol", null, "{\"name\":\"a\",\"value\":\"3\"}",
                "{\"name\":\"b\",\"value\":\"4\"}");

        // 完整替换后态含重复名 → 整笔拒绝：不产生新版本、不改当前指向、放行状态不变
        mvc.perform(put("/api/measurements/{key}/budget", "U-5").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"a","value":"1"},
                                 {"name":"new","value":"2"},{"name":"new","value":"3"}],
                                 "expectedVersion":2}"""))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/measurements/{key}/budget", "U-5"))
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.combinedUncertainty").value("5.0000"))
                .andExpect(jsonPath("$.components.length()").value(2));
        mvc.perform(get("/api/measurements/{key}", "U-5"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(2));
        Integer componentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component c JOIN uncertainty_budget b ON b.id = c.budget_id "
                        + "JOIN measurement m ON m.id = b.measurement_id WHERE m.measurement_key = 'U-5'",
                Integer.class);
        assertEquals(2, componentRows, "失败修订不得留下部分分量");
    }

    @Test
    void releaseGatesOnCombinedUncertaintyAndFreezesSnapshot() throws Exception {
        createCert("INS-1");
        publishThreshold("INS-1", "4", "admin");
        submit("U-6", "INS-1", "1", "alice");
        // [3,4] 合成 5 > 阈值 4 → 拒绝
        saveBudget("U-6", "carol", null, "{\"name\":\"a\",\"value\":\"3\"}",
                "{\"name\":\"b\",\"value\":\"4\"}");

        MvcResult blocked = release("dave", "U-6");
        assertEquals(409, blocked.getResponse().getStatus());
        mvc.perform(get("/api/measurements/{key}", "U-6"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 阈值修订到 5（更宽）后，旧预算按其固化阈值 4 判定仍失败——放行读取一致预算，阈值版本不改写旧快照
        publishThreshold("INS-1", "5", "admin");
        mvc.perform(get("/api/measurements/{key}/budget", "U-6"))
                .andExpect(jsonPath("$.thresholdVersion").value(1))
                .andExpect(jsonPath("$.required").value("4"))
                .andExpect(jsonPath("$.withinThreshold").value(false));
        assertEquals(409, release("dave", "U-6").getResponse().getStatus());

        // 重新登记预算（完整替换），固化当前阈值版本 2，合成 5 ≤ 5 → 放行成功
        clock.advanceSeconds(10);
        saveBudget("U-6", "carol", "2", "{\"name\":\"a\",\"value\":\"3\"}",
                "{\"name\":\"b\",\"value\":\"4\"}");
        mvc.perform(get("/api/measurements/{key}/budget", "U-6"))
                .andExpect(jsonPath("$.budgetVersion").value(2))
                .andExpect(jsonPath("$.thresholdVersion").value(2))
                .andExpect(jsonPath("$.withinThreshold").value(true));
        assertEquals(200, release("dave", "U-6").getResponse().getStatus());

        // 再发布更严阈值不改写已放行快照
        publishThreshold("INS-1", "1", "admin");
        mvc.perform(get("/api/measurements/{key}", "U-6"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releases[0].thresholdVersion").value(2))
                .andExpect(jsonPath("$.releases[0].thresholdValue").value("5"))
                .andExpect(jsonPath("$.releases[0].combinedUncertainty").value("5.0000"))
                .andExpect(jsonPath("$.releases[0].calcMethod").value("RSS_V1"))
                .andExpect(jsonPath("$.releases[0].budgetId").isNumber());
    }

    @Test
    void thresholdBoundaryUsesUnroundedStorageValue() throws Exception {
        createCert("INS-B");
        // 阈值恰好四位显示边界：真实合成 sqrt(0.1²+0.1²)=0.14142135...，显示 0.1414；阈值 0.1414
        publishThreshold("INS-B", "0.1414", "admin");
        submit("U-B", "INS-B", "1", "alice");
        saveBudget("U-B", "carol", null, "{\"name\":\"a\",\"value\":\"0.1\"}",
                "{\"name\":\"b\",\"value\":\"0.1\"}");
        // 0.14142135... > 0.1414 → 虽然显示都是 0.1414，按未舍入值必须拒绝
        assertEquals(409, release("dave", "U-B").getResponse().getStatus());
        mvc.perform(get("/api/measurements/{key}/budget", "U-B"))
                .andExpect(jsonPath("$.combinedUncertainty").value("0.1414"))
                .andExpect(jsonPath("$.withinThreshold").value(false))
                // 未舍入差额 0.1414 − 0.141421356237 ≈ -0.000021356237（暴露四位显示掩盖的超出）
                .andExpect(jsonPath("$.difference").value("-0.000021356237"));
    }

    @Test
    void budgetBatchWithOneOverThresholdRejectsWholeReleaseBatch() throws Exception {
        createCert("INS-1");
        publishThreshold("INS-1", "5", "admin");
        submit("U-A", "INS-1", "1", "alice");
        submit("U-C", "INS-1", "1", "bob");
        saveBudget("U-A", "carol", null, "{\"name\":\"a\",\"value\":\"3\"}",
                "{\"name\":\"b\",\"value\":\"4\"}"); // 5 ≤ 5
        saveBudget("U-C", "carol", null, "{\"name\":\"a\",\"value\":\"3\"}",
                "{\"name\":\"b\",\"value\":\"4.1\"}"); // sqrt(25.81)≈5.08 > 5

        MvcResult result = release("dave", "U-A", "U-C");
        assertEquals(409, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        java.util.List<?> reasons = JsonPath.read(body, "$.failures[?(@.key=='U-C')].reasons[0]");
        assertEquals(java.util.List.of("UNCERTAINTY_EXCEEDED"), reasons);
        // 整批回滚：U-A 也不得被放行
        mvc.perform(get("/api/measurements/{key}", "U-A"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void revisionAfterReleaseRejected409AndEvidenceFrozen() throws Exception {
        createCert("INS-1");
        publishThreshold("INS-1", "5", "admin");
        submit("U-7", "INS-1", "1", "alice");
        saveBudget("U-7", "carol", null, "{\"name\":\"a\",\"value\":\"1\"}");
        assertEquals(200, release("dave", "U-7").getResponse().getStatus());

        mvc.perform(put("/api/measurements/{key}/budget", "U-7").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"a","value":"2"}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEASUREMENT_RELEASED"));
        // 旧证据不回写
        mvc.perform(get("/api/measurements/{key}/budget", "U-7"))
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.components[0].value").value("1"));
    }

    @Test
    void requestIdReplayReturnsFirstSnapshotAndMismatchConflictsAndFailureDoesNotOccupy()
            throws Exception {
        createCert("INS-1");
        publishThreshold("INS-1", "9", "admin");
        submit("U-8", "INS-1", "1", "alice");

        String budgetBody = "{\"components\":[{\"name\":\"a\",\"value\":\"3\"},"
                + "{\"name\":\"b\",\"value\":\"4\"}]}";
        MvcResult first = mvc.perform(put("/api/measurements/{key}/budget", "U-8")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON).content(budgetBody))
                .andExpect(status().isOk()).andReturn();
        long firstBudgetId = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.id")).longValue();

        // 同参重放（分量顺序变化但集合相同）→ 200，首次完整响应快照（同一预算版本 ID）
        String replayBody = "{\"components\":[{\"name\":\"b\",\"value\":\"4\"},"
                + "{\"name\":\"a\",\"value\":\"3\"}]}";
        MvcResult replay = mvc.perform(put("/api/measurements/{key}/budget", "U-8")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON).content(replayBody))
                .andExpect(status().isOk()).andReturn();
        assertEquals(firstBudgetId,
                ((Number) JsonPath.read(replay.getResponse().getContentAsString(), "$.id")).longValue());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "成功重放必须返回首次完整响应快照");

        // 异参（值变化）→ 409
        mvc.perform(put("/api/measurements/{key}/budget", "U-8")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"a","value":"3"},{"name":"b","value":"4.1"}]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));

        // 失败不占键：REQ-2 先用重复名失败（422），随后同 REQ-2 合法请求应当成功
        mvc.perform(put("/api/measurements/{key}/budget", "U-8")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"x","value":"1"},{"name":"x","value":"2"}]}"""))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/measurements/{key}/budget", "U-8")
                        .header("X-Actor-Id", "carol").header("X-Request-Id", "REQ-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"a","value":"1"}]}"""))
                .andExpect(status().isOk());

        // 阈值发布同参重放：版本号不重复增加
        mvc.perform(post("/api/thresholds").header("X-Actor-Id", "admin").header("X-Request-Id", "REQ-T")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","maxUncertainty":"9.5"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/api/thresholds").header("X-Actor-Id", "admin").header("X-Request-Id", "REQ-T")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","maxUncertainty":"9.5"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/api/thresholds").header("X-Actor-Id", "admin").header("X-Request-Id", "REQ-T")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","maxUncertainty":"9.6"}"""))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/thresholds/current").param("instrumentId", "INS-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.maxUncertainty").value("9.5"));
    }

    @Test
    void historyIsImmutableVersionedAndQueriesDoNotMutate() throws Exception {
        createCert("INS-H");
        publishThreshold("INS-H", "10", "admin");
        submit("U-H", "INS-H", "1", "alice");

        clock.advanceSeconds(5);
        saveBudget("U-H", "carol", null, "{\"name\":\"a\",\"value\":\"3\"}",
                "{\"name\":\"b\",\"value\":\"4\"}");
        String firstHistory = mvc.perform(get("/api/measurements/{key}/budget/history", "U-H"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].thresholdVersion").value(1))
                .andReturn().getResponse().getContentAsString();

        // 阈值修订 + 预算修订：新版本固化新阈值，旧版本不变
        clock.advanceSeconds(5);
        publishThreshold("INS-H", "20", "admin");
        clock.advanceSeconds(5);
        saveBudget("U-H", "carol", "2", "{\"name\":\"a\",\"value\":\"6\"}",
                "{\"name\":\"c\",\"value\":\"8\"}");

        mvc.perform(get("/api/measurements/{key}/budget/history", "U-H"))
                .andExpect(jsonPath("$.measurementVersion").value(3))
                .andExpect(jsonPath("$.versions.length()").value(2))
                // v1：阈值 10、合成 5、旧分量集合
                .andExpect(jsonPath("$.versions[0].budgetVersion").value(1))
                .andExpect(jsonPath("$.versions[0].thresholdVersion").value(1))
                .andExpect(jsonPath("$.versions[0].thresholdValue").value("10"))
                .andExpect(jsonPath("$.versions[0].combinedUncertainty").value("5.0000"))
                .andExpect(jsonPath("$.versions[0].components[0].name").value("a"))
                .andExpect(jsonPath("$.versions[0].components[1].name").value("b"))
                // v2：阈值 20、合成 10、新分量集合（完整替换，b 已不在）
                .andExpect(jsonPath("$.versions[1].budgetVersion").value(2))
                .andExpect(jsonPath("$.versions[1].thresholdVersion").value(2))
                .andExpect(jsonPath("$.versions[1].thresholdValue").value("20"))
                .andExpect(jsonPath("$.versions[1].combinedUncertainty").value("10.0000"))
                .andExpect(jsonPath("$.versions[1].components.length()").value(2))
                .andExpect(jsonPath("$.versions[1].components[0].name").value("a"))
                .andExpect(jsonPath("$.versions[1].components[1].name").value("c"));

        // 只读查询重复调用结果一致（含时间微秒精度与 null 语义稳定）
        String again = mvc.perform(get("/api/measurements/{key}/budget/history", "U-H"))
                .andReturn().getResponse().getContentAsString();
        String currentTwice1 = mvc.perform(get("/api/measurements/{key}/budget", "U-H"))
                .andReturn().getResponse().getContentAsString();
        String currentTwice2 = mvc.perform(get("/api/measurements/{key}/budget", "U-H"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(currentTwice1, currentTwice2, "查询不得改变状态");
        assertNotEquals(firstHistory, again);
        // 时间戳为 UTC 且精确到微秒：v1 创建于 START+5s
        mvc.perform(get("/api/measurements/{key}/budget/history", "U-H"))
                .andExpect(jsonPath("$.versions[0].createdAt")
                        .value("2026-06-01T00:00:05Z"))
                .andExpect(jsonPath("$.versions[1].createdAt")
                        .value("2026-06-01T00:00:15Z"));
    }

    @Test
    void unknownMeasurementBudgetEndpointsReturn404() throws Exception {
        mvc.perform(get("/api/measurements/{key}/budget", "NO-SUCH"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/measurements/{key}/budget/history", "NO-SUCH"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/measurements/{key}/budget", "NO-SUCH")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"components":[{"name":"a","value":"1"}]}"""))
                .andExpect(status().isNotFound());
    }
}
