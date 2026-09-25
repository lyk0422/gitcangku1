package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测质量标记 API 测试（真实 H2 内存库，MySQL 兼容模式）：
 * 覆盖标记创建规则、复核前置校验、版本一致性与 STALE、置信度重算与固化、
 * 幂等边界以及标记历史/置信度轨迹/待复核清单查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QualityFlagApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM quality_flag_review");
        jdbcTemplate.update("DELETE FROM quality_flag");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createObservation(String requestId, String observationId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "初始备注");
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergeNote(String requestId, String observationId, int baseVersion, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions createFlag(String requestId, String observationId, String flagKey,
                                     String category, String description, String submittedBy)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("flagKey", flagKey);
        body.put("category", category);
        body.put("description", description);
        body.put("submittedBy", submittedBy);
        return mockMvc.perform(post("/api/observations/{id}/flags", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions review(String requestId, String flagKey, String conclusion,
                                 String reason, String reviewedBy) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("conclusion", conclusion);
        body.put("reason", reason);
        body.put("reviewedBy", reviewedBy);
        return mockMvc.perform(post("/api/observations/flags/{key}/reviews", flagKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int confidenceOfVersion(String observationId, int version) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT confidence FROM observation_version WHERE observation_id = ? AND version = ?",
                Integer.class, observationId, version);
        return value == null ? -1 : value;
    }

    private int reviewCount(String flagKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag_review WHERE flag_key = ?", Integer.class, flagKey);
        return count == null ? 0 : count;
    }

    // ---------- 标记创建 ----------

    @Test
    void createFlagAttachesToCurrentVersionWithoutChangingObservation() throws Exception {
        createObservation("req-o1", "obs-1").andExpect(status().isCreated());

        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_ANOMALY", "读数持续跳变", "field-role")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagKey").value("flag-1"))
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.category").value("SENSOR_ANOMALY"))
                .andExpect(jsonPath("$.description").value("读数持续跳变"))
                .andExpect(jsonPath("$.submittedBy").value("field-role"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reviewedBy").doesNotExist())
                .andExpect(jsonPath("$.createdAt").exists());

        // 标记不改变观测内容、版本与置信度
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.confidence").value(100))
                .andExpect(jsonPath("$.note").value("初始备注"));
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-1'", Integer.class);
        assertThat(versionRows).isEqualTo(1);
    }

    @Test
    void duplicatePendingFlagOfSameCategoryReturns409ButOtherCategoryAccepted() throws Exception {
        createObservation("req-o2", "obs-1").andExpect(status().isCreated());
        createFlag("req-f2", "obs-1", "flag-1", "SENSOR_ANOMALY", "异常1", "role-a")
                .andExpect(status().isCreated());

        // 同一观测同一类别同时只能有一条待复核标记
        createFlag("req-f3", "obs-1", "flag-2", "SENSOR_ANOMALY", "异常2", "role-b")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        // 不同类别允许
        createFlag("req-f4", "obs-1", "flag-3", "HUMAN_MISOPERATION", "误操作", "role-b")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag WHERE observation_id = 'obs-1'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void sameCategoryCanBeFlaggedAgainAfterPreviousFlagReviewed() throws Exception {
        createObservation("req-o3", "obs-1").andExpect(status().isCreated());
        createFlag("req-f5", "obs-1", "flag-1", "SENSOR_ANOMALY", "异常1", "role-a")
                .andExpect(status().isCreated());
        review("req-r5", "flag-1", "DISMISSED", "复核为正常波动", "role-q")
                .andExpect(status().isOk());

        // 前一条已离开待复核状态，同类别可以再次提交
        createFlag("req-f6", "obs-1", "flag-2", "SENSOR_ANOMALY", "异常2", "role-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void duplicateFlagKeyReturns409() throws Exception {
        createObservation("req-o4", "obs-1").andExpect(status().isCreated());
        createFlag("req-f7", "obs-1", "flag-x", "SENSOR_ANOMALY", "异常", "role-a")
                .andExpect(status().isCreated());
        createFlag("req-f8", "obs-1", "flag-x", "ENVIRONMENTAL_INTERFERENCE", "干扰", "role-b")
                .andExpect(status().isConflict());
    }

    @Test
    void createFlagFailures() throws Exception {
        // 观测不存在
        createFlag("req-f9", "obs-x", "flag-1", "SENSOR_ANOMALY", "异常", "role-a")
                .andExpect(status().isNotFound());
        createObservation("req-o5", "obs-1").andExpect(status().isCreated());
        // 观测已删除
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-d5");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deleteBody))).andExpect(status().isOk());
        createFlag("req-f10", "obs-1", "flag-2", "SENSOR_ANOMALY", "异常", "role-a")
                .andExpect(status().isGone());
        // 非法类别
        createFlag("req-f11", "obs-1", "flag-3", "NOT_A_CATEGORY", "异常", "role-a")
                .andExpect(status().isBadRequest());
    }

    // ---------- 复核与置信度 ----------

    @Test
    void confirmedReviewDeductsConfidenceAndFreezesCurrentVersion() throws Exception {
        createObservation("req-o6", "obs-1").andExpect(status().isCreated());
        createFlag("req-f12", "obs-1", "flag-1", "SENSOR_ANOMALY", "读数跳变", "role-a")
                .andExpect(status().isCreated());

        review("req-r12", "flag-1", "CONFIRMED", "复核确认传感器故障", "role-q")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagKey").value("flag-1"))
                .andExpect(jsonPath("$.flagVersion").value(1))
                .andExpect(jsonPath("$.reviewVersion").value(1))
                .andExpect(jsonPath("$.conclusion").value("CONFIRMED"))
                .andExpect(jsonPath("$.reason").value("复核确认传感器故障"))
                .andExpect(jsonPath("$.reviewedBy").value("role-q"))
                .andExpect(jsonPath("$.confidenceAfter").value(80))
                .andExpect(jsonPath("$.confidenceChanged").value(true));

        // 当前状态与当前版本快照均为 80
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.confidence").value(80));
        assertThat(confidenceOfVersion("obs-1", 1)).isEqualTo(80);
        // 标记终态与不可变复核记录
        mockMvc.perform(get("/api/observations/{id}/flags", "obs-1"))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].reviewVersion").value(1));
        assertThat(reviewCount("flag-1")).isEqualTo(1);
    }

    @Test
    void dismissedReviewDoesNotChangeConfidence() throws Exception {
        createObservation("req-o7", "obs-1").andExpect(status().isCreated());
        createFlag("req-f13", "obs-1", "flag-1", "SENSOR_ANOMALY", "疑似异常", "role-a")
                .andExpect(status().isCreated());

        review("req-r13", "flag-1", "DISMISSED", "数据正常", "role-q")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidenceAfter").value(100))
                .andExpect(jsonPath("$.confidenceChanged").value(false));

        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.confidence").value(100));
        assertThat(confidenceOfVersion("obs-1", 1)).isEqualTo(100);
    }

    @Test
    void eachDifferentCategoryConfirmedDeductsOnceAndConfidenceFloorsAtZeroAcrossVersions()
            throws Exception {
        createObservation("req-o8", "obs-1").andExpect(status().isCreated());
        createFlag("req-f20", "obs-1", "f-sensor", "SENSOR_ANOMALY", "s", "role-a")
                .andExpect(status().isCreated());
        review("req-r20", "f-sensor", "CONFIRMED", "ok", "role-q").andExpect(status().isOk());
        createFlag("req-f21", "obs-1", "f-human", "HUMAN_MISOPERATION", "h", "role-a")
                .andExpect(status().isCreated());
        review("req-r21", "f-human", "CONFIRMED", "ok", "role-q").andExpect(status().isOk());
        createFlag("req-f22", "obs-1", "f-env", "ENVIRONMENTAL_INTERFERENCE", "e", "role-a")
                .andExpect(status().isCreated());
        review("req-r22", "f-env", "CONFIRMED", "ok", "role-q").andExpect(status().isOk());
        // v1：三个不同类别各扣 20 → 40
        assertThat(confidenceOfVersion("obs-1", 1)).isEqualTo(40);

        // 同版本同类别再次 CONFIRMED 不重复扣减
        createFlag("req-f23", "obs-1", "f-sensor-2", "SENSOR_ANOMALY", "s2", "role-a")
                .andExpect(status().isCreated());
        review("req-r23", "f-sensor-2", "CONFIRMED", "ok", "role-q")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidenceAfter").value(40))
                .andExpect(jsonPath("$.confidenceChanged").value(false));
        assertThat(confidenceOfVersion("obs-1", 1)).isEqualTo(40);

        // 新合并产生 v2：继承 40；新版本后同类别可再次生效扣减
        mergeNote("req-m20", "obs-1", 1, "新版本备注").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.confidence").value(40));
        createFlag("req-f24", "obs-1", "f-sensor-v2", "SENSOR_ANOMALY", "s", "role-a")
                .andExpect(status().isCreated());
        review("req-r24", "f-sensor-v2", "CONFIRMED", "ok", "role-q")
                .andExpect(jsonPath("$.confidenceAfter").value(20));
        createFlag("req-f25", "obs-1", "f-human-v2", "HUMAN_MISOPERATION", "h", "role-a")
                .andExpect(status().isCreated());
        review("req-r25", "f-human-v2", "CONFIRMED", "ok", "role-q")
                .andExpect(jsonPath("$.confidenceAfter").value(0));
        createFlag("req-f26", "obs-1", "f-env-v2", "ENVIRONMENTAL_INTERFERENCE", "e", "role-a")
                .andExpect(status().isCreated());
        review("req-r26", "f-env-v2", "CONFIRMED", "ok", "role-q")
                .andExpect(jsonPath("$.confidenceAfter").value(0));

        // 最低 0；历史快照固化：v1 永远 40，v2 最终 0
        assertThat(confidenceOfVersion("obs-1", 1)).isEqualTo(40);
        assertThat(confidenceOfVersion("obs-1", 2)).isZero();
    }

    @Test
    void historicalSnapshotConfidenceIsNeverRewrittenByLaterReviews() throws Exception {
        createObservation("req-o9", "obs-1").andExpect(status().isCreated());
        createFlag("req-f30", "obs-1", "f1", "SENSOR_ANOMALY", "s", "role-a")
                .andExpect(status().isCreated());
        review("req-r30", "f1", "CONFIRMED", "ok", "role-q").andExpect(status().isOk());
        // v1 固化为 80
        mergeNote("req-m30", "obs-1", 1, "v2备注").andExpect(status().isOk());
        createFlag("req-f31", "obs-1", "f2", "HUMAN_MISOPERATION", "h", "role-a")
                .andExpect(status().isCreated());
        review("req-r31", "f2", "CONFIRMED", "ok", "role-q").andExpect(status().isOk());

        // v2 为 60；v1 的历史快照仍为 80，不被改写
        mockMvc.perform(get("/api/observations/{id}/versions/{v}", "obs-1", 1))
                .andExpect(jsonPath("$.confidence").value(80));
        mockMvc.perform(get("/api/observations/{id}/versions/{v}", "obs-1", 2))
                .andExpect(jsonPath("$.confidence").value(60));
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.confidence").value(60));
    }

    // ---------- 复核前置校验与 STALE ----------

    @Test
    void reviewerSameAsSubmitterReturns403() throws Exception {
        createObservation("req-o10", "obs-1").andExpect(status().isCreated());
        createFlag("req-f40", "obs-1", "f1", "SENSOR_ANOMALY", "s", "same-role")
                .andExpect(status().isCreated());
        review("req-r40", "f1", "CONFIRMED", "自复核", "same-role")
                .andExpect(status().isForbidden());
        // 标记仍待复核，置信度不变
        mockMvc.perform(get("/api/observations/{id}/flags", "obs-1"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        assertThat(confidenceOfVersion("obs-1", 1)).isEqualTo(100);
    }

    @Test
    void reviewAfterNewVersionReturns410AndFlagBecomesStale() throws Exception {
        createObservation("req-o11", "obs-1").andExpect(status().isCreated());
        createFlag("req-f50", "obs-1", "f1", "SENSOR_ANOMALY", "s", "role-a")
                .andExpect(status().isCreated());
        // 观测产生新版本 v2
        mergeNote("req-m50", "obs-1", 1, "v2备注").andExpect(status().isOk());

        review("req-r50", "f1", "CONFIRMED", "滞后的复核", "role-q")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.currentVersion").value(2));

        // 标记转 STALE，不再可复核，也不影响新版本置信度
        mockMvc.perform(get("/api/observations/{id}/flags", "obs-1"))
                .andExpect(jsonPath("$[0].status").value("STALE"))
                .andExpect(jsonPath("$[0].reviewVersion").doesNotExist());
        review("req-r51", "f1", "CONFIRMED", "再次复核", "role-q")
                .andExpect(status().isGone());
        assertThat(confidenceOfVersion("obs-1", 2)).isEqualTo(100);
        assertThat(reviewCount("f1")).isZero();
    }

    @Test
    void reviewAfterObservationDeletedReturns410AndFlagBecomesStale() throws Exception {
        createObservation("req-o12", "obs-1").andExpect(status().isCreated());
        createFlag("req-f60", "obs-1", "f1", "SENSOR_ANOMALY", "s", "role-a")
                .andExpect(status().isCreated());
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-d60");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(deleteBody))).andExpect(status().isOk());

        review("req-r60", "f1", "CONFIRMED", "滞后复核", "role-q")
                .andExpect(status().isGone());
        mockMvc.perform(get("/api/observations/{id}/flags", "obs-1"))
                .andExpect(jsonPath("$[0].status").value("STALE"));
    }

    @Test
    void reviewOfPendingFlagBoundToOldVersionStillReturns410AndGoesStaleWithoutOccupyingKey()
            throws Exception {
        // 防御性路径：正常情况下合并/删除会主动转 STALE；此处手工植入绑定旧版本的 PENDING 标记，
        // 模拟任何遗漏场景，复核仍必须回滚（不占 requestId）、转 STALE、不扣减。
        createObservation("req-o11b", "obs-1b").andExpect(status().isCreated());
        mergeNote("req-m11b", "obs-1b", 1, "v2备注").andExpect(status().isOk());
        jdbcTemplate.update(
                "INSERT INTO quality_flag (flag_key, observation_id, version, category, description, "
                        + "submitted_by, status, pending_dedup_key, created_at) "
                        + "VALUES ('f-old', 'obs-1b', 1, 'SENSOR_ANOMALY', '旧版本标记', 'role-a', "
                        + "'PENDING', NULL, CURRENT_TIMESTAMP)");

        review("req-r11b", "f-old", "CONFIRMED", "滞后复核", "role-q")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.currentVersion").value(2));
        mockMvc.perform(get("/api/observations/{id}/flags", "obs-1b"))
                .andExpect(jsonPath("$[0].status").value("STALE"));
        // 主事务回滚：requestId 未被占用，可用于新标记的合法复核
        createFlag("req-f11b", "obs-1b", "f-new", "SENSOR_ANOMALY", "v2标记", "role-a")
                .andExpect(status().isCreated());
        review("req-r11b", "f-new", "CONFIRMED", "当前版本复核", "role-q")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidenceAfter").value(80));
    }

    @Test
    void reviewOfAlreadyReviewedOrMissingFlagFails() throws Exception {        createObservation("req-o13", "obs-1").andExpect(status().isCreated());
        createFlag("req-f70", "obs-1", "f1", "SENSOR_ANOMALY", "s", "role-a")
                .andExpect(status().isCreated());
        review("req-r70", "f1", "CONFIRMED", "首次", "role-q").andExpect(status().isOk());
        // 已复核标记不可再次复核
        review("req-r71", "f1", "DISMISSED", "二次", "role-z")
                .andExpect(status().isConflict());
        // 不存在的标记
        review("req-r72", "flag-missing", "CONFIRMED", "x", "role-q")
                .andExpect(status().isNotFound());
        // 不可变记录只有一条
        assertThat(reviewCount("f1")).isEqualTo(1);
    }

    // ---------- 幂等 ----------

    @Test
    void flagRequestIdReplayRules() throws Exception {
        createObservation("req-o14", "obs-1").andExpect(status().isCreated());

        // 同键同参重放首次结果：两次都 201，只落一条标记
        createFlag("req-fi1", "obs-1", "f1", "SENSOR_ANOMALY", "异常", "role-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagKey").value("f1"));
        createFlag("req-fi1", "obs-1", "f1", "SENSOR_ANOMALY", "异常", "role-a")
                .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quality_flag WHERE flag_key = 'f1'", Integer.class)).isEqualTo(1);

        // 同键异参 409
        createFlag("req-fi1", "obs-1", "f1", "SENSOR_ANOMALY", "改过的说明", "role-a")
                .andExpect(status().isConflict());

        // 失败不占键：先用该键对不存在观测提交失败，再对存在观测提交成功（换用空闲类别）
        createFlag("req-fi2", "obs-x", "f2", "ENVIRONMENTAL_INTERFERENCE", "干扰", "role-a")
                .andExpect(status().isNotFound());
        createFlag("req-fi2", "obs-1", "f2", "ENVIRONMENTAL_INTERFERENCE", "干扰", "role-a")
                .andExpect(status().isCreated());
    }

    @Test
    void reviewRequestIdReplayRules() throws Exception {
        createObservation("req-o15", "obs-1").andExpect(status().isCreated());
        createFlag("req-f80", "obs-1", "f1", "SENSOR_ANOMALY", "异常", "role-a")
                .andExpect(status().isCreated());

        // 同键同参重放：即便后续产生新版本，重放仍返回首次的成功结果且不新增复核记录
        review("req-ri1", "f1", "CONFIRMED", "确认", "role-q")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidenceAfter").value(80));
        mergeNote("req-m80", "obs-1", 1, "v2备注").andExpect(status().isOk());
        review("req-ri1", "f1", "CONFIRMED", "确认", "role-q")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidenceAfter").value(80))
                .andExpect(jsonPath("$.reviewVersion").value(1));
        assertThat(reviewCount("f1")).isEqualTo(1);

        // 同键异参 409
        review("req-ri1", "f1", "CONFIRMED", "不同理由", "role-q")
                .andExpect(status().isConflict());

        // 失败不占键：同键以“自复核”失败（参数 X），换合法复核人与参数后成功
        createFlag("req-f81", "obs-1", "f2", "HUMAN_MISOPERATION", "误操作", "role-a")
                .andExpect(status().isCreated());
        review("req-ri2", "f2", "CONFIRMED", "自复核", "role-a")
                .andExpect(status().isForbidden());
        review("req-ri2", "f2", "CONFIRMED", "合法复核", "role-q")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confidenceAfter").value(60));
    }

    // ---------- 查询 ----------

    @Test
    void flagHistoryPendingListAndConfidenceTrailQueries() throws Exception {
        createObservation("req-o16", "obs-1").andExpect(status().isCreated());
        createFlag("req-f90", "obs-1", "f1", "SENSOR_ANOMALY", "传感器", "role-a")
                .andExpect(status().isCreated());
        review("req-r90", "f1", "CONFIRMED", "确认", "role-q").andExpect(status().isOk());
        createFlag("req-f91", "obs-1", "f2", "HUMAN_MISOPERATION", "误操作", "role-a")
                .andExpect(status().isCreated());
        // f3 绑定 v1，之后观测前进到 v2，f3 不应出现在待复核清单
        createFlag("req-f92", "obs-1", "f3", "ENVIRONMENTAL_INTERFERENCE", "干扰", "role-a")
                .andExpect(status().isCreated());
        mergeNote("req-m90", "obs-1", 1, "v2备注").andExpect(status().isOk());

        // 待复核清单：合并产生 v2 时 f2、f3 已随版本变化主动转 STALE，清单为空
        mockMvc.perform(get("/api/observations/{id}/pending-flags", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());

        // STALE 标记不可复核：返回 410，不产生复核记录
        review("req-r91", "f2", "DISMISSED", "滞后", "role-q").andExpect(status().isGone());

        // 在当前版本 v2 新建标记，进入待复核清单
        createFlag("req-f93", "obs-1", "f4", "SENSOR_ANOMALY", "v2传感器", "role-a")
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/observations/{id}/pending-flags", "obs-1"))
                .andExpect(jsonPath("$[0].flagKey").value("f4"))
                .andExpect(jsonPath("$[0].version").value(2));

        // 标记历史：f1 CONFIRMED、f2 STALE、f3 STALE、f4 PENDING
        mockMvc.perform(get("/api/observations/{id}/flags", "obs-1"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].flagKey").value("f1"))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[1].flagKey").value("f2"))
                .andExpect(jsonPath("$[1].status").value("STALE"))
                .andExpect(jsonPath("$[2].flagKey").value("f3"))
                .andExpect(jsonPath("$[2].status").value("STALE"))
                .andExpect(jsonPath("$[3].flagKey").value("f4"))
                .andExpect(jsonPath("$[3].status").value("PENDING"));

        // 已 STALE 的 f3 再次复核仍返回 410
        review("req-r92", "f3", "CONFIRMED", "滞后", "role-q")
                .andExpect(status().isGone());

        // 置信度轨迹：INITIAL 100 → CONFIRMED 80（v1）→ VERSION 80（v2）
        mockMvc.perform(get("/api/observations/{id}/confidence-trail", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventType").value("INITIAL"))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].confidence").value(100))
                .andExpect(jsonPath("$[1].eventType").value("CONFIRMED"))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].confidence").value(80))
                .andExpect(jsonPath("$[1].delta").value(-20))
                .andExpect(jsonPath("$[1].category").value("SENSOR_ANOMALY"))
                .andExpect(jsonPath("$[2].eventType").value("VERSION"))
                .andExpect(jsonPath("$[2].version").value(2))
                .andExpect(jsonPath("$[2].confidence").value(80));

        // 对不存在观测的查询返回 404
        mockMvc.perform(get("/api/observations/{id}/flags", "obs-x"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/confidence-trail", "obs-x"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/pending-flags", "obs-x"))
                .andExpect(status().isNotFound());
    }
}
