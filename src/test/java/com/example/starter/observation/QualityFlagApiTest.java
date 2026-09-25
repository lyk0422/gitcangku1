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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测质量标记 API 测试：标记创建规则、复核前置校验、置信度重算、
 * 标记历史/待复核清单/置信度轨迹查询与幂等边界（真实 H2 内存库，MySQL 兼容模式）。
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
        jdbcTemplate.update("DELETE FROM confidence_deduction");
        jdbcTemplate.update("DELETE FROM quality_flag");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createObs(String requestId, String observationId) throws Exception {
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

    private ResultActions mergeObs(String requestId, String observationId, int baseVersion,
                                   String location) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", "初始备注");
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteObs(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions createFlag(String requestId, String observationId, String flagKey,
                                     String category, String description, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("flagKey", flagKey);
        body.put("category", category);
        body.put("description", description);
        body.put("role", role);
        return mockMvc.perform(post("/api/observations/{id}/flags", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions review(String requestId, String observationId, String flagKey,
                                 String conclusion, String reason, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("flagKey", flagKey);
        body.put("conclusion", conclusion);
        body.put("reason", reason);
        body.put("role", role);
        return mockMvc.perform(post("/api/observations/{id}/flags/review", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private ResultActions getVersion(String observationId, int version) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/versions/{version}", observationId, version));
    }

    private ResultActions listFlags(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/flags", observationId));
    }

    private ResultActions listPending(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/flags/pending", observationId));
    }

    private ResultActions confidenceTrajectory(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/confidence", observationId));
    }

    // ---------- 标记创建 ----------

    @Test
    void createFlagBindsCurrentVersionWithoutChangingObservation() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());

        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "读数漂移", "OBSERVER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagKey").value("flag-1"))
                .andExpect(jsonPath("$.category").value("SENSOR_FAULT"))
                .andExpect(jsonPath("$.description").value("读数漂移"))
                .andExpect(jsonPath("$.submittedRole").value("OBSERVER"))
                .andExpect(jsonPath("$.boundVersion").value(1))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.conclusion").doesNotExist());

        // 标记不改变观测内容与版本，置信度不变
        getCurrent("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.confidence").value(100));

        // 待复核清单包含该标记
        listPending("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].flagKey").value("flag-1"))
                .andExpect(jsonPath("$[0].status").value("PENDING_REVIEW"));
    }

    @Test
    void duplicatePendingCategoryReturns409UntilReviewed() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        // 同一观测同一类别已存在待复核标记：409
        createFlag("req-f2", "obs-1", "flag-2", "SENSOR_FAULT", "再次漂移", "OPERATOR")
                .andExpect(status().isConflict());
        // 不同类别不受影响
        createFlag("req-f3", "obs-1", "flag-3", "HUMAN_ERROR", "误抄", "OPERATOR")
                .andExpect(status().isCreated());

        // 原标记复核完成后，同类别可再次提交
        review("req-r1", "obs-1", "flag-1", "DISMISSED", "误报", "REVIEWER")
                .andExpect(status().isOk());
        createFlag("req-f4", "obs-1", "flag-4", "SENSOR_FAULT", "新的漂移", "OPERATOR")
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateFlagKeyReturns409() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        createFlag("req-f2", "obs-1", "flag-1", "HUMAN_ERROR", "同键不同类", "OPERATOR")
                .andExpect(status().isConflict());
    }

    @Test
    void flagOnMissingOrDeletedObservationRejected() throws Exception {
        createFlag("req-f1", "obs-x", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isNotFound());

        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        deleteObs("req-d1", "obs-1", 1).andExpect(status().isOk());
        createFlag("req-f2", "obs-1", "flag-2", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isGone());
    }

    @Test
    void invalidCategoryReturns400() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "UNKNOWN_CATEGORY", "漂移", "OBSERVER")
                .andExpect(status().isBadRequest());
    }

    // ---------- 复核 ----------

    @Test
    void confirmedReviewDeductsConfidenceAndWritesImmutableRecord() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());

        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "现场核实属实", "REVIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagKey").value("flag-1"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.conclusion").value("CONFIRMED"))
                .andExpect(jsonPath("$.reason").value("现场核实属实"))
                .andExpect(jsonPath("$.reviewerRole").value("REVIEWER"))
                .andExpect(jsonPath("$.flaggedVersion").value(1))
                .andExpect(jsonPath("$.reviewVersion").value(1))
                .andExpect(jsonPath("$.versionConsistent").value(true));

        // 置信度 100 -> 80，观测版本不变
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.confidence").value(80));

        // 标记历史携带复核记录
        listFlags("obs-1")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].conclusion").value("CONFIRMED"));
        // 待复核清单清空
        listPending("obs-1").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void dismissedReviewKeepsConfidence() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "HUMAN_ERROR", "误抄", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r1", "obs-1", "flag-1", "DISMISSED", "证据不足", "REVIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.conclusion").value("DISMISSED"));
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(100));
    }

    @Test
    void reviewBySameRoleReturns409() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "自己复核自己", "OBSERVER")
                .andExpect(status().isConflict());
        // 标记仍待复核，置信度不变
        listPending("obs-1").andExpect(jsonPath("$", hasSize(1)));
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(100));
    }

    @Test
    void reviewMissingFlagOrObservationReturns404() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        review("req-r1", "obs-1", "flag-x", "CONFIRMED", "理由", "REVIEWER")
                .andExpect(status().isNotFound());
        review("req-r2", "obs-x", "flag-1", "CONFIRMED", "理由", "REVIEWER")
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewAlreadyReviewedFlagReturns409() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isOk());
        review("req-r2", "obs-1", "flag-1", "DISMISSED", "改判", "AUDITOR")
                .andExpect(status().isConflict());
        // 复核记录不可变：仍为首次结论
        listFlags("obs-1")
                .andExpect(jsonPath("$[0].conclusion").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].reason").value("属实"));
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(80));
    }

    @Test
    void reviewAfterNewVersionReturns410AndTurnsStale() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        // 观测产生新版本
        mergeObs("req-m1", "obs-1", 1, "站点B").andExpect(status().isOk());

        // 复核时版本已变化：410，标记转 STALE
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.currentVersion").value(2));
        listFlags("obs-1")
                .andExpect(jsonPath("$[0].status").value("STALE"))
                .andExpect(jsonPath("$[0].conclusion").doesNotExist());
        // 置信度不受影响
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(100));

        // 同键同参重放同一 410 结果
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410));
        // STALE 标记不再可复核
        review("req-r2", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isGone());

        // STALE 不占用类别：新版本上可再次提交同类别标记并复核生效
        createFlag("req-f2", "obs-1", "flag-2", "SENSOR_FAULT", "新漂移", "OBSERVER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.boundVersion").value(2));
        review("req-r3", "obs-1", "flag-2", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isOk());
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(80));
    }

    @Test
    void reviewAfterDeleteReturns410AndTurnsStale() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        deleteObs("req-d1", "obs-1", 1).andExpect(status().isOk());
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isGone());
        listFlags("obs-1").andExpect(jsonPath("$[0].status").value("STALE"));
    }

    // ---------- 置信度 ----------

    @Test
    void sameCategoryConfirmedDeductsOncePerVersion() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        // 版本 1 上同类别两次 CONFIRMED：只扣减一次
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移1", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isOk());
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(80));

        createFlag("req-f2", "obs-1", "flag-2", "SENSOR_FAULT", "漂移2", "OPERATOR")
                .andExpect(status().isCreated());
        review("req-r2", "obs-1", "flag-2", "CONFIRMED", "仍属实", "REVIEWER")
                .andExpect(status().isOk());
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(80));

        // 新合并产生新版本后，该类别可再次生效扣减
        mergeObs("req-m1", "obs-1", 1, "站点B").andExpect(status().isOk());
        createFlag("req-f3", "obs-1", "flag-3", "SENSOR_FAULT", "漂移3", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r3", "obs-1", "flag-3", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isOk());
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(60));
    }

    @Test
    void distinctCategoriesDeductSeparatelyWithFloorAtZero() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        // 版本 1：三个不同类别各扣 20 -> 40
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        createFlag("req-f2", "obs-1", "flag-2", "HUMAN_ERROR", "误抄", "OBSERVER")
                .andExpect(status().isCreated());
        createFlag("req-f3", "obs-1", "flag-3", "ENVIRONMENT_INTERFERENCE", "雷击", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER").andExpect(status().isOk());
        review("req-r2", "obs-1", "flag-2", "CONFIRMED", "属实", "REVIEWER").andExpect(status().isOk());
        review("req-r3", "obs-1", "flag-3", "CONFIRMED", "属实", "REVIEWER").andExpect(status().isOk());
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(40));

        // 版本 2：三个类别再各扣 20 -> 最低 0，不出现负数
        mergeObs("req-m1", "obs-1", 1, "站点B").andExpect(status().isOk());
        createFlag("req-f4", "obs-1", "flag-4", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        createFlag("req-f5", "obs-1", "flag-5", "HUMAN_ERROR", "误抄", "OBSERVER")
                .andExpect(status().isCreated());
        createFlag("req-f6", "obs-1", "flag-6", "ENVIRONMENT_INTERFERENCE", "雷击", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r4", "obs-1", "flag-4", "CONFIRMED", "属实", "REVIEWER").andExpect(status().isOk());
        review("req-r5", "obs-1", "flag-5", "CONFIRMED", "属实", "REVIEWER").andExpect(status().isOk());
        review("req-r6", "obs-1", "flag-6", "CONFIRMED", "属实", "REVIEWER").andExpect(status().isOk());
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(0));
    }

    @Test
    void confidenceTrajectoryPreservedPerVersion() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isOk());
        // 合并产生版本 2：置信度随版本继承（80）
        mergeObs("req-m1", "obs-1", 1, "站点B").andExpect(status().isOk());
        createFlag("req-f2", "obs-1", "flag-2", "HUMAN_ERROR", "误抄", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r2", "obs-1", "flag-2", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isOk());

        // 置信度轨迹：v1=80，v2=60
        confidenceTrajectory("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].confidence").value(80))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].confidence").value(60));

        // 历史快照不被后续版本改写
        getVersion("obs-1", 1).andExpect(jsonPath("$.confidence").value(80));
        getVersion("obs-1", 2).andExpect(jsonPath("$.confidence").value(60));
    }

    // ---------- 查询 ----------

    @Test
    void queriesOnMissingObservationReturn404() throws Exception {
        listFlags("obs-x").andExpect(status().isNotFound());
        listPending("obs-x").andExpect(status().isNotFound());
        confidenceTrajectory("obs-x").andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReplaysFlagCreation() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        // 同键同参重放：返回首次结果，不产生重复标记
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagKey").value("flag-1"));
        listFlags("obs-1").andExpect(jsonPath("$", hasSize(1)));

        // 同键异参：409
        createFlag("req-f1", "obs-1", "flag-2", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isConflict());
        createFlag("req-f1", "obs-1", "flag-1", "HUMAN_ERROR", "漂移", "OBSERVER")
                .andExpect(status().isConflict());
    }

    @Test
    void sameRequestIdReplaysReviewAndDeductsOnce() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isOk());
        // 同键同参重放：返回首次结果，置信度只扣减一次
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "属实", "REVIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("CONFIRMED"));
        getCurrent("obs-1").andExpect(jsonPath("$.confidence").value(80));
        // 同键异参：409
        review("req-r1", "obs-1", "flag-1", "DISMISSED", "属实", "REVIEWER")
                .andExpect(status().isConflict());
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        createObs("req-o1", "obs-1").andExpect(status().isCreated());
        createFlag("req-f1", "obs-1", "flag-1", "SENSOR_FAULT", "漂移", "OBSERVER")
                .andExpect(status().isCreated());
        // 409 失败不占键：同类别重复标记
        createFlag("req-f2", "obs-1", "flag-2", "SENSOR_FAULT", "重复", "OPERATOR")
                .andExpect(status().isConflict());
        // 同一 requestId 换上合法参数后正常执行
        createFlag("req-f2", "obs-1", "flag-2", "HUMAN_ERROR", "误抄", "OPERATOR")
                .andExpect(status().isCreated());

        // 复核 409（同人复核）失败不占键
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "自己复核", "OBSERVER")
                .andExpect(status().isConflict());
        review("req-r1", "obs-1", "flag-1", "CONFIRMED", "换人复核", "REVIEWER")
                .andExpect(status().isOk());
    }
}
