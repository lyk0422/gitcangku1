package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测更正附页 API 测试：版本链与原值保存、差异校验（422）、corrKey 幂等、撤销与恢复、
 * 裁决冻结与待复审标记、冲突簇/导出视图使用最新有效附页、批量事务与幂等。
 * 使用真实 H2 内存库与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorrigendumApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-22T10:15:30Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM re_review_marker");
        jdbcTemplate.update("DELETE FROM corrigendum_revocation");
        jdbcTemplate.update("DELETE FROM observation_corrigendum");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private ResultActions create(String requestId, String observationId,
                                 String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolve(String requestId, String resolutionId, String observationId,
                                  int baseVersion, int expectedCurrentVersion,
                                  String location, String reading, String note,
                                  Map<String, String> selections, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        body.put("selections", selections);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions submitCorr(String corrKey, String observationId, int baseVersion,
                                     Map<String, String> diffs, String reason, String collector) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("corrKey", corrKey);
        body.put("baseVersion", baseVersion);
        body.put("diffs", diffs);
        body.put("reason", reason);
        body.put("collector", collector);
        return mockMvc.perform(post("/api/observations/{id}/corrigenda", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions submitBatch(String requestId, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/corrigenda/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> batchItem(String corrKey, String observationId, int baseVersion,
                                          Map<String, String> diffs, String reason, String collector) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("corrKey", corrKey);
        item.put("observationId", observationId);
        item.put("baseVersion", baseVersion);
        item.put("diffs", diffs);
        item.put("reason", reason);
        item.put("collector", collector);
        return item;
    }

    private ResultActions revoke(String requestId, String observationId,
                                 int corrVersion, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("corrVersion", corrVersion);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/corrigenda/revoke", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int corrigendumCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_corrigendum WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int requestLogCount(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：版本链、原值保存、导出视图 ----------

    @Test
    void submitCorrigendumBuildsVersionChainAndExportUsesLatestValid() throws Exception {
        create("req-c0", "obs-c1", "站点A", "1.0", "备注").andExpect(status().isCreated());

        // 第一张附页：更正地点，保存原值与更正值
        submitCorr("ck-1", "obs-c1", 1, Map.of("location", "站点C"), "现场复核", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("obs-c1"))
                .andExpect(jsonPath("$.corrVersion").value(1))
                .andExpect(jsonPath("$.corrKey").value("ck-1"))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.diffs.location").value("站点C"))
                .andExpect(jsonPath("$.originalValues.location").value("站点A"))
                .andExpect(jsonPath("$.reason").value("现场复核"))
                .andExpect(jsonPath("$.collector").value("collector-a"))
                .andExpect(jsonPath("$.revoked").value(false))
                .andExpect(jsonPath("$.createdAtUtc").value("2026-09-22T10:15:30Z"));

        // 第二张附页：更正读数，版本递增；读数 1.50 规范化为 1.5
        submitCorr("ck-2", "obs-c1", 1, Map.of("reading", "1.50"), "仪器校准", "collector-b")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(2))
                .andExpect(jsonPath("$.diffs.reading").value("1.5"))
                .andExpect(jsonPath("$.originalValues.reading").value("1.0"));

        // 原始观测不可覆盖：当前版本与历史版本保持原值
        mockMvc.perform(get("/api/observations/{id}", "obs-c1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.reading").value("1.0"));

        // 附页链按版本先后返回，含原值与更正值
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].corrVersion").value(1))
                .andExpect(jsonPath("$[0].diffs.location").value("站点C"))
                .andExpect(jsonPath("$[0].originalValues.location").value("站点A"))
                .andExpect(jsonPath("$[1].corrVersion").value(2))
                .andExpect(jsonPath("$[1].diffs.reading").value("1.5"));

        // 导出视图：未裁决观测仅应用最新有效附页（读数 1.5），地点保持原值
        mockMvc.perform(get("/api/observations/{id}/export", "obs-c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.resolved").value(false))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.reading").value("1.5"))
                .andExpect(jsonPath("$.note").value("备注"))
                .andExpect(jsonPath("$.appliedCorrigendumVersion").value(2))
                .andExpect(jsonPath("$.pendingReReviewCount").value(0));
    }

    // ---------- 差异校验：空差异/未知字段/更正值边界 422 ----------

    @Test
    void submitInvalidDiffsReturn422AndOccupyNoKey() throws Exception {
        create("req-v0", "obs-c2", "站点A", "1.0", "备注").andExpect(status().isCreated());

        // 空差异 422
        submitCorr("ck-v1", "obs-c2", 1, Map.of(), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("diffs must not be empty"));
        // 未知字段 422
        submitCorr("ck-v2", "obs-c2", 1, Map.of("temperature", "36.5"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity());
        // 读数格式非法 422
        submitCorr("ck-v3", "obs-c2", 1, Map.of("reading", "1.2345"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity());
        // 读数超出最终坐标边界 422
        submitCorr("ck-v4", "obs-c2", 1, Map.of("reading", "1000000000"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity());
        // 地点更正值为空 422
        submitCorr("ck-v5", "obs-c2", 1, Map.of("location", "  "), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity());

        // 失败不占键、不留半成品：没有任何附页，request_log 无记录
        assertThat(corrigendumCount("obs-c2")).isZero();
        assertThat(requestLogCount("ck-v1")).isZero();
        assertThat(requestLogCount("ck-v4")).isZero();

        // 同一 corrKey 换上合法差异后成功
        submitCorr("ck-v1", "obs-c2", 1, Map.of("note", "更正备注"), "原因", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(1));
        assertThat(corrigendumCount("obs-c2")).isEqualTo(1);
    }

    @Test
    void submitOnMissingObservationOrBaseVersionReturns404() throws Exception {
        submitCorr("ck-m1", "obs-missing", 1, Map.of("note", "x"), "原因", "collector-a")
                .andExpect(status().isNotFound());
        create("req-m0", "obs-c3", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitCorr("ck-m2", "obs-c3", 9, Map.of("note", "x"), "原因", "collector-a")
                .andExpect(status().isNotFound());
        assertThat(corrigendumCount("obs-c3")).isZero();
        assertThat(requestLogCount("ck-m2")).isZero();
    }

    @Test
    void submitOnDeletedObservationReturns410() throws Exception {
        create("req-d0", "obs-c4", "站点A", "1.0", "备注").andExpect(status().isCreated());
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-d1");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-c4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());
        submitCorr("ck-d1", "obs-c4", 1, Map.of("note", "x"), "原因", "collector-a")
                .andExpect(status().isGone());
        assertThat(corrigendumCount("obs-c4")).isZero();
    }

    // ---------- corrKey 幂等：同键重放、异参 409、读数规范化 ----------

    @Test
    void corrKeyReplayReturnsOriginalAndChangedParamsReturn409() throws Exception {
        create("req-k0", "obs-c5", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitCorr("ck-k1", "obs-c5", 1, Map.of("reading", "2.50"), "原因", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(1))
                .andExpect(jsonPath("$.diffs.reading").value("2.5"));

        // 同键同参重放（读数 2.5 与 2.50 规范化后相同）：返回原结果，不产生新附页
        submitCorr("ck-k1", "obs-c5", 1, Map.of("reading", "2.5"), "原因", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(1));
        assertThat(corrigendumCount("obs-c5")).isEqualTo(1);
        assertThat(requestLogCount("ck-k1")).isEqualTo(1);

        // 同键异参：改原因 409、改差异 409、改采集者 409、改原版本 409
        submitCorr("ck-k1", "obs-c5", 1, Map.of("reading", "2.5"), "其他原因", "collector-a")
                .andExpect(status().isConflict());
        submitCorr("ck-k1", "obs-c5", 1, Map.of("reading", "3.0"), "原因", "collector-a")
                .andExpect(status().isConflict());
        submitCorr("ck-k1", "obs-c5", 1, Map.of("reading", "2.5"), "原因", "collector-b")
                .andExpect(status().isConflict());
        assertThat(corrigendumCount("obs-c5")).isEqualTo(1);
    }

    // ---------- 撤销：仅最新有效附页，恢复上一个有效版本，不可变撤销记录 ----------

    @Test
    void revokeOnlyLatestValidAndRestoresPreviousValid() throws Exception {
        create("req-r0", "obs-c6", "站点A", "1.0", "备注").andExpect(status().isCreated());
        submitCorr("ck-r1", "obs-c6", 1, Map.of("location", "站点B"), "原因1", "collector-a")
                .andExpect(status().isCreated());
        submitCorr("ck-r2", "obs-c6", 1, Map.of("location", "站点C"), "原因2", "collector-a")
                .andExpect(status().isCreated());

        // 非最新有效附页不可撤销：409
        revoke("req-rv0", "obs-c6", 1, "operator-a")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "only the latest valid corrigendum can be revoked: obs-c6#1, latest valid is #2"));

        // 撤销最新附页 v2：写入不可变撤销记录，导出视图恢复上一有效版本 v1
        revoke("req-rv1", "obs-c6", 2, "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationId").value("obs-c6"))
                .andExpect(jsonPath("$.corrVersion").value(2))
                .andExpect(jsonPath("$.requestId").value("req-rv1"))
                .andExpect(jsonPath("$.operator").value("operator-a"))
                .andExpect(jsonPath("$.revokedAtUtc").value("2026-09-22T10:15:30Z"));
        mockMvc.perform(get("/api/observations/{id}/export", "obs-c6"))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.appliedCorrigendumVersion").value(1));

        // 撤销请求同键重放：返回原撤销记录，不重复撤销
        revoke("req-rv1", "obs-c6", 2, "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrVersion").value(2));
        // 已撤销的 v2 不是最新有效附页：再次撤销 409
        revoke("req-rv2", "obs-c6", 2, "operator-a").andExpect(status().isConflict());

        // 撤销 v1 后无有效附页：导出视图回到原始观测值
        revoke("req-rv3", "obs-c6", 1, "operator-b").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/{id}/export", "obs-c6"))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.appliedCorrigendumVersion").doesNotExist());
        // 无有效附页可撤销：409
        revoke("req-rv4", "obs-c6", 1, "operator-b").andExpect(status().isConflict());

        // 附页链保留全部历史（含撤销标记），撤销历史不可变可查
        // （固定时钟下撤销时刻相同，按附页版本次序稳定返回）
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-c6"))
                .andExpect(jsonPath("$[0].revoked").value(true))
                .andExpect(jsonPath("$[1].revoked").value(true));
        mockMvc.perform(get("/api/observations/{id}/corrigenda/revocations", "obs-c6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].corrVersion").value(1))
                .andExpect(jsonPath("$[0].operator").value("operator-b"))
                .andExpect(jsonPath("$[1].corrVersion").value(2))
                .andExpect(jsonPath("$[1].operator").value("operator-a"));
    }

    @Test
    void revokeMissingCorrigendumOrObservationReturns404() throws Exception {
        create("req-rm0", "obs-c7", "站点A", "1.0", "备注").andExpect(status().isCreated());
        revoke("req-rm1", "obs-c7", 3, "operator-a").andExpect(status().isNotFound());
        revoke("req-rm2", "obs-missing", 1, "operator-a").andExpect(status().isNotFound());
    }

    // ---------- 裁决冻结与待复审标记 ----------

    @Test
    void corrigendumAfterResolutionFreezesResolutionAndMarksReReview() throws Exception {
        // 构造冲突并人工裁决：v1=站点A，服务端 v2=站点B，离线候选站点C 裁决为 CANDIDATE → v3=站点C
        create("req-f0", "obs-c8", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-f1", "obs-c8", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        merge("req-f2", "obs-c8", 1, "站点C", "1.0", "备注").andExpect(status().isConflict());
        resolve("req-f3", "res-c8", "obs-c8", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        // 裁决后提交附页：成功落库，但不改写裁决结果
        submitCorr("ck-f1", "obs-c8", 3, Map.of("location", "站点Z"), "裁决后更正", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(1));

        // 裁决结果冻结：解决记录仍指向裁决时版本与内容
        mockMvc.perform(get("/api/observations/resolutions/{resolutionId}", "res-c8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点C"));
        // 导出视图：已裁决观测冻结裁决结果，附页不生效
        mockMvc.perform(get("/api/observations/{id}/export", "obs-c8"))
                .andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.appliedCorrigendumVersion").doesNotExist())
                .andExpect(jsonPath("$.pendingReReviewCount").value(1));
        // 生成待复审标记
        mockMvc.perform(get("/api/observations/{id}/re-reviews", "obs-c8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].resolutionId").value("res-c8"))
                .andExpect(jsonPath("$[0].corrVersion").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].createdAtUtc").value("2026-09-22T10:15:30Z"));

        // 第二张附页再生成一条标记；裁决结果依然不变
        submitCorr("ck-f2", "obs-c8", 3, Map.of("note", "新备注"), "再次更正", "collector-b")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(2));
        mockMvc.perform(get("/api/observations/{id}/re-reviews", "obs-c8"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].corrVersion").value(2));
        mockMvc.perform(get("/api/observations/resolutions/{resolutionId}", "res-c8"))
                .andExpect(jsonPath("$.location").value("站点C"));
    }

    // ---------- 冲突簇生成使用最新有效附页 ----------

    @Test
    void mergeConflictClusterUsesLatestValidCorrigendum() throws Exception {
        create("req-g0", "obs-c9", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-g1", "obs-c9", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 未裁决观测：附页把有效地点更正为站点C
        submitCorr("ck-g1", "obs-c9", 2, Map.of("location", "站点C"), "现场复核", "collector-a")
                .andExpect(status().isCreated());

        // 离线端基于 v1 提交站点C：与有效当前值相同，无冲突直接接受
        merge("req-g2", "obs-c9", 1, "站点C", "1.0", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点C"));

        // 离线端基于 v1 提交站点D：与有效当前值站点C 冲突
        merge("req-g3", "obs-c9", 1, "站点D", "1.0", "备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(3));
    }

    // ---------- 批量提交：整批校验、原子回滚、幂等 ----------

    @Test
    void batchSubmitCreatesCorrigendaInOrderAndReplays() throws Exception {
        create("req-b0", "obs-b1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-b1", "obs-b2", "站点X", "2.0", "备注").andExpect(status().isCreated());

        List<Map<String, Object>> items = List.of(
                batchItem("ck-b1", "obs-b1", 1, Map.of("location", "站点B"), "原因1", "collector-a"),
                batchItem("ck-b2", "obs-b1", 1, Map.of("reading", "1.5"), "原因2", "collector-a"),
                batchItem("ck-b3", "obs-b2", 1, Map.of("note", "更正备注"), "原因3", "collector-b"));
        submitBatch("req-bt1", items)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].observationId").value("obs-b1"))
                .andExpect(jsonPath("$[0].corrVersion").value(1))
                .andExpect(jsonPath("$[1].observationId").value("obs-b1"))
                .andExpect(jsonPath("$[1].corrVersion").value(2))
                .andExpect(jsonPath("$[2].observationId").value("obs-b2"))
                .andExpect(jsonPath("$[2].corrVersion").value(1));

        // 整批同键重放：返回原结果，不重复产生附页
        submitBatch("req-bt1", items)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3));
        assertThat(corrigendumCount("obs-b1")).isEqualTo(2);
        assertThat(corrigendumCount("obs-b2")).isEqualTo(1);
        assertThat(requestLogCount("req-bt1")).isEqualTo(1);

        // 同键异参：409
        submitBatch("req-bt1", List.of(
                        batchItem("ck-b9", "obs-b1", 1, Map.of("note", "异参"), "原因", "collector-a")))
                .andExpect(status().isConflict());
        assertThat(corrigendumCount("obs-b1")).isEqualTo(2);
    }

    @Test
    void batchValidationFailureRollsBackEntireBatch() throws Exception {
        create("req-bz0", "obs-b3", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-bz1", "obs-b4", "站点X", "2.0", "备注").andExpect(status().isCreated());

        // 第二条目未知字段：整批 422，第一条目也不产生附页
        submitBatch("req-bz1x", List.of(
                        batchItem("ck-bz1", "obs-b3", 1, Map.of("location", "站点B"), "原因", "collector-a"),
                        batchItem("ck-bz2", "obs-b4", 1, Map.of("temperature", "36"), "原因", "collector-b")))
                .andExpect(status().isUnprocessableEntity());
        // 第二条目超出最终坐标边界：整批 422
        submitBatch("req-bz2x", List.of(
                        batchItem("ck-bz3", "obs-b3", 1, Map.of("location", "站点B"), "原因", "collector-a"),
                        batchItem("ck-bz4", "obs-b4", 1, Map.of("reading", "9999999999"), "原因", "collector-b")))
                .andExpect(status().isUnprocessableEntity());
        // 第二条目原观测不存在：整批 404
        submitBatch("req-bz3x", List.of(
                        batchItem("ck-bz5", "obs-b3", 1, Map.of("location", "站点B"), "原因", "collector-a"),
                        batchItem("ck-bz6", "obs-missing", 1, Map.of("note", "x"), "原因", "collector-b")))
                .andExpect(status().isNotFound());

        // 整批回滚：不产生任何附页或重算，失败不占键
        assertThat(corrigendumCount("obs-b3")).isZero();
        assertThat(corrigendumCount("obs-b4")).isZero();
        assertThat(requestLogCount("req-bz1x")).isZero();
        assertThat(requestLogCount("req-bz2x")).isZero();
        assertThat(requestLogCount("req-bz3x")).isZero();

        // 修正后同一 requestId 重新提交成功
        submitBatch("req-bz1x", List.of(
                        batchItem("ck-bz1", "obs-b3", 1, Map.of("location", "站点B"), "原因", "collector-a")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(corrigendumCount("obs-b3")).isEqualTo(1);
    }

    // ---------- 查询边界 ----------

    @Test
    void corrigendumQueriesOnMissingObservationReturn404() throws Exception {
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/corrigenda/revocations", "obs-missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/re-reviews", "obs-missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/export", "obs-missing"))
                .andExpect(status().isNotFound());
    }
}
