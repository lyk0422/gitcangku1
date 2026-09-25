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
 * 观测更正附页 API 测试：版本链、422 校验、坐标边界、幂等重放、批量事务、
 * 裁决版本冻结与待复审标记、撤销与恢复。使用真实 H2 内存库与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorrigendumApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-26T08:30:00Z");

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
        jdbcTemplate.update("DELETE FROM review_flag");
        jdbcTemplate.update("DELETE FROM corrigendum_revocation");
        jdbcTemplate.update("DELETE FROM corrigendum");
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

    private ResultActions submitCorr(String corrKey, String observationId, int baseVersion,
                                     Map<String, String> diffs, String reason, String collector)
            throws Exception {
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

    private ResultActions submitBatch(String corrKey, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("corrKey", corrKey);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/corrigenda/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> batchItem(String observationId, int baseVersion,
                                          Map<String, String> diffs, String reason, String collector) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", observationId);
        item.put("baseVersion", baseVersion);
        item.put("diffs", diffs);
        item.put("reason", reason);
        item.put("collector", collector);
        return item;
    }

    private ResultActions revoke(String corrKey, String observationId, int corrVersion,
                                 String operator, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("corrKey", corrKey);
        body.put("corrVersion", corrVersion);
        body.put("operator", operator);
        if (reason != null) {
            body.put("reason", reason);
        }
        return mockMvc.perform(post("/api/observations/{id}/corrigenda/revoke", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int corrigendumCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corrigendum WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int requestLogCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void submitCorrigendumBuildsVersionChainAndEffectiveView() throws Exception {
        create("req-c1", "obs-c1", "30.000,120.000", "1.0", "初始备注")
                .andExpect(status().isCreated());

        submitCorr("ck-c1-1", "obs-c1", 1, Map.of("location", "30.500,120.500"), "坐标纠偏", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(1))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.diffs.location.from").value("30.000,120.000"))
                .andExpect(jsonPath("$.diffs.location.to").value("30.500,120.500"))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.collector").value("collector-a"))
                .andExpect(jsonPath("$.createdAtUtc").value("2026-09-26T08:30:00Z"));

        submitCorr("ck-c1-2", "obs-c1", 1, Map.of("reading", "2.500"), "读数复测", "collector-b")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(2))
                .andExpect(jsonPath("$.diffs.reading.from").value("1.0"))
                .andExpect(jsonPath("$.diffs.reading.to").value("2.500"));

        // 附页链按版本递增，原始观测版本不被覆盖
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].corrVersion").value(1))
                .andExpect(jsonPath("$[1].corrVersion").value(2));
        mockMvc.perform(get("/api/observations/{id}", "obs-c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("30.000,120.000"));

        // 导出视图：未裁决观测应用最新有效附页（v1 地点 + v2 读数），原始值保留
        mockMvc.perform(get("/api/observations/{id}/view", "obs-c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicated").value(false))
                .andExpect(jsonPath("$.pendingReview").value(false))
                .andExpect(jsonPath("$.originalLocation").value("30.000,120.000"))
                .andExpect(jsonPath("$.originalReading").value("1.0"))
                .andExpect(jsonPath("$.effectiveLocation").value("30.500,120.500"))
                .andExpect(jsonPath("$.effectiveReading").value("2.500"))
                .andExpect(jsonPath("$.effectiveNote").value("初始备注"));
    }

    @Test
    void submitRejectsEmptyDiffUnknownFieldNoopAndBadReading() throws Exception {
        create("req-c2", "obs-c2", "30.000,120.000", "1.0", "备注").andExpect(status().isCreated());

        submitCorr("ck-c2-empty", "obs-c2", 1, Map.of(), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("empty")));
        submitCorr("ck-c2-unknown", "obs-c2", 1, Map.of("altitude", "10"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unknown")));
        submitCorr("ck-c2-noop", "obs-c2", 1, Map.of("note", "备注"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no change")));
        submitCorr("ck-c2-badreading", "obs-c2", 1, Map.of("reading", "1.2345"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity());

        // 全部失败不产生附页、不占幂等键（request_log 仅有创建记录一条）
        assertThat(corrigendumCount("obs-c2")).isZero();
        assertThat(requestLogCount()).isEqualTo(1);
    }

    @Test
    void submitValidatesFinalCoordinateBounds() throws Exception {
        create("req-c3", "obs-c3", "30.000,120.000", "1.0", "备注").andExpect(status().isCreated());

        // 纬度越界
        submitCorr("ck-c3-lat", "obs-c3", 1, Map.of("location", "91.000,120.000"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("bounds")));
        // 经度越界
        submitCorr("ck-c3-lon", "obs-c3", 1, Map.of("location", "30.000,181.000"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity());
        // 非坐标格式
        submitCorr("ck-c3-fmt", "obs-c3", 1, Map.of("location", "站点B"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("lat,lon")));
        // 边界值合法
        submitCorr("ck-c3-edge", "obs-c3", 1, Map.of("location", "-90.000,180.000"), "原因", "collector-a")
                .andExpect(status().isCreated());

        assertThat(corrigendumCount("obs-c3")).isEqualTo(1);
    }

    @Test
    void submitChecksBaseVersionExistenceAndDeletion() throws Exception {
        create("req-c4", "obs-c4", "30.000,120.000", "1.0", "备注").andExpect(status().isCreated());

        submitCorr("ck-c4-404", "obs-missing", 1, Map.of("note", "x"), "原因", "collector-a")
                .andExpect(status().isNotFound());

        // 观测推进到 v2 后，旧 baseVersion 拒绝
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-c4-m");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "30.000,120.000");
        mergeBody.put("reading", "9.9");
        mergeBody.put("note", "备注");
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-c4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());
        submitCorr("ck-c4-stale", "obs-c4", 1, Map.of("note", "新备注"), "原因", "collector-a")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));

        // 删除后拒绝新增附页
        Map<String, Object> deleteBody = Map.of("requestId", "req-c4-d", "expectedVersion", 2);
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-c4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());
        submitCorr("ck-c4-gone", "obs-c4", 3, Map.of("note", "新备注"), "原因", "collector-a")
                .andExpect(status().isGone());

        assertThat(corrigendumCount("obs-c4")).isZero();
    }

    @Test
    void corrKeyReplaySameParamsAndConflictOnDifferentParams() throws Exception {
        create("req-c5", "obs-c5", "30.000,120.000", "1.0", "备注").andExpect(status().isCreated());

        submitCorr("ck-c5", "obs-c5", 1, Map.of("reading", "3.0"), "复测", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(1));
        // 同键同参重放：返回原结果，不产生新附页
        submitCorr("ck-c5", "obs-c5", 1, Map.of("reading", "3.0"), "复测", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(1));
        assertThat(corrigendumCount("obs-c5")).isEqualTo(1);

        // 同键异参 409
        submitCorr("ck-c5", "obs-c5", 1, Map.of("reading", "4.0"), "复测", "collector-a")
                .andExpect(status().isConflict());

        // 失败不占键：先 422 后用同键修正参数成功
        submitCorr("ck-c5-fail", "obs-c5", 1, Map.of("unknown", "x"), "原因", "collector-a")
                .andExpect(status().isUnprocessableEntity());
        submitCorr("ck-c5-fail", "obs-c5", 1, Map.of("note", "修正备注"), "原因", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(2));
    }

    @Test
    void adjudicatedObservationFreezesViewAndFlagsPendingReview() throws Exception {
        create("req-c6", "obs-c6", "30.000,120.000", "1.0", "备注").andExpect(status().isCreated());
        // 服务端推进到 v2，离线端基于 v1 造成地点冲突后人工裁决
        Map<String, Object> merge1 = new LinkedHashMap<>();
        merge1.put("requestId", "req-c6-m1");
        merge1.put("baseVersion", 1);
        merge1.put("location", "31.000,121.000");
        merge1.put("reading", "1.0");
        merge1.put("note", "备注");
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-c6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(merge1)))
                .andExpect(status().isOk());
        Map<String, Object> resolveBody = new LinkedHashMap<>();
        resolveBody.put("requestId", "req-c6-r");
        resolveBody.put("resolutionId", "res-c6");
        resolveBody.put("baseVersion", 1);
        resolveBody.put("expectedCurrentVersion", 2);
        resolveBody.put("location", "32.000,122.000");
        resolveBody.put("reading", "1.0");
        resolveBody.put("note", "备注");
        resolveBody.put("selections", Map.of("location", "CANDIDATE"));
        resolveBody.put("operator", "operator-a");
        mockMvc.perform(post("/api/observations/{id}/resolve", "obs-c6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        // 裁决后提交附页：成功但不改写裁决结果，生成待复审标记
        submitCorr("ck-c6", "obs-c6", 3, Map.of("reading", "7.7"), "读数复核", "collector-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrVersion").value(1));

        mockMvc.perform(get("/api/observations/{id}/view", "obs-c6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjudicated").value(true))
                .andExpect(jsonPath("$.adjudicatedVersion").value(3))
                .andExpect(jsonPath("$.pendingReview").value(true))
                .andExpect(jsonPath("$.effectiveLocation").value("32.000,122.000"))
                .andExpect(jsonPath("$.effectiveReading").value("1.0"));

        mockMvc.perform(get("/api/observations/{id}/review-flags", "obs-c6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].resolutionId").value("res-c6"))
                .andExpect(jsonPath("$[0].corrVersion").value(1))
                .andExpect(jsonPath("$[0].event").value("SUBMIT"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void revokeLatestCorrigendumRestoresPreviousEffectiveVersion() throws Exception {
        create("req-c7", "obs-c7", "30.000,120.000", "1.0", "备注").andExpect(status().isCreated());
        submitCorr("ck-c7-1", "obs-c7", 1, Map.of("location", "30.500,120.500"), "纠偏一", "collector-a")
                .andExpect(status().isCreated());
        submitCorr("ck-c7-2", "obs-c7", 1, Map.of("location", "30.600,120.600"), "纠偏二", "collector-a")
                .andExpect(status().isCreated());

        // 非最新有效版本拒绝撤销
        revoke("ck-c7-r0", "obs-c7", 1, "operator-a", "先撤旧的")
                .andExpect(status().isConflict());

        // 撤销最新版本：恢复上一个有效版本
        revoke("ck-c7-r1", "obs-c7", 2, "operator-a", "纠偏二有误")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrVersion").value(2))
                .andExpect(jsonPath("$.restoredCorrVersion").value(1))
                .andExpect(jsonPath("$.revokedAtUtc").value("2026-09-26T08:30:00Z"));
        mockMvc.perform(get("/api/observations/{id}/view", "obs-c7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveLocation").value("30.500,120.500"));

        // 同键重放撤销结果，不重复写撤销记录
        revoke("ck-c7-r1", "obs-c7", 2, "operator-a", "纠偏二有误")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrVersion").value(2));
        mockMvc.perform(get("/api/observations/{id}/corrigenda/revocations", "obs-c7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 再撤销 v1：恢复原始观测值
        revoke("ck-c7-r2", "obs-c7", 1, "operator-b", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredCorrVersion").doesNotExist());
        mockMvc.perform(get("/api/observations/{id}/view", "obs-c7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveLocation").value("30.000,120.000"));

        // 无有效附页可撤
        revoke("ck-c7-r3", "obs-c7", 1, "operator-b", null)
                .andExpect(status().isConflict());

        // 附页链保留全部历史（含已撤销），撤销记录不可变
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-c7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[1].status").value("REVOKED"));
        mockMvc.perform(get("/api/observations/{id}/corrigenda/revocations", "obs-c7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void batchSubmitIsAllOrNothing() throws Exception {
        create("req-c8-a", "obs-c8a", "30.000,120.000", "1.0", "备注A").andExpect(status().isCreated());
        create("req-c8-b", "obs-c8b", "40.000,110.000", "2.0", "备注B").andExpect(status().isCreated());

        // 任一条目最终坐标越界：整批 422，两条观测都不产生附页
        submitBatch("ck-c8-fail", List.of(
                        batchItem("obs-c8a", 1, Map.of("reading", "1.5"), "复测A", "collector-a"),
                        batchItem("obs-c8b", 1, Map.of("location", "95.000,110.000"), "纠偏B", "collector-b")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(corrigendumCount("obs-c8a")).isZero();
        assertThat(corrigendumCount("obs-c8b")).isZero();

        // 失败不占键：修正后同键重提成功
        submitBatch("ck-c8-fail", List.of(
                        batchItem("obs-c8a", 1, Map.of("reading", "1.5"), "复测A", "collector-a"),
                        batchItem("obs-c8b", 1, Map.of("location", "45.000,110.000"), "纠偏B", "collector-b")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].observationId").value("obs-c8a"))
                .andExpect(jsonPath("$[0].corrVersion").value(1))
                .andExpect(jsonPath("$[1].observationId").value("obs-c8b"))
                .andExpect(jsonPath("$[1].corrVersion").value(1));

        // 整批同键重放
        submitBatch("ck-c8-fail", List.of(
                        batchItem("obs-c8a", 1, Map.of("reading", "1.5"), "复测A", "collector-a"),
                        batchItem("obs-c8b", 1, Map.of("location", "45.000,110.000"), "纠偏B", "collector-b")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(corrigendumCount("obs-c8a")).isEqualTo(1);
        assertThat(corrigendumCount("obs-c8b")).isEqualTo(1);

        // 批内重复观测 422
        submitBatch("ck-c8-dup", List.of(
                        batchItem("obs-c8a", 1, Map.of("note", "x"), "r", "collector-a"),
                        batchItem("obs-c8a", 1, Map.of("note", "y"), "r", "collector-a")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(corrigendumCount("obs-c8a")).isEqualTo(1);
    }

    @Test
    void queriesRequireExistingObservation() throws Exception {
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-none"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/corrigenda/revocations", "obs-none"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/review-flags", "obs-none"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/view", "obs-none"))
                .andExpect(status().isNotFound());
        revoke("ck-none", "obs-none", 1, "operator-a", null)
                .andExpect(status().isNotFound());
        submitCorr("ck-none-2", "obs-none", 1, Map.of("note", "x"), "r", "collector-a")
                .andExpect(status().isNotFound());
    }
}
