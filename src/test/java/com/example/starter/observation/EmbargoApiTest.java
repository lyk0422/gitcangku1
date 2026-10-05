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
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测禁运与到期发布 API 测试：主流程、422/409/410/404 失败分支、批量原子性、
 * 幂等重放、快照不可变与附页追加（真实 H2 内存库，MySQL 兼容模式，可控 UTC 时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmbargoApiTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T01:00:00Z");
    private static final String T1_TEXT = "2026-01-01T01:00:00Z";

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM disclosure_addendum");
        jdbcTemplate.update("DELETE FROM disclosure_snapshot");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM open_conflict");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        now.set(T0);
        Mockito.when(clock.instant()).thenAnswer(invocation -> now.get());
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
                                  String location, Map<String, String> selections,
                                  String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("selections", selections);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions delete(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> item(String observationId, String embargoUntilUtc, List<String> scope) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", observationId);
        item.put("embargoUntilUtc", embargoUntilUtc);
        item.put("scope", scope);
        return item;
    }

    private ResultActions setEmbargo(String requestId, String operator,
                                     List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/embargo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions release(String requestId, String observationId, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/embargo/release", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getPublic(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private ResultActions getEvidence(String observationId, String operator) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/evidence", observationId)
                .param("operator", operator));
    }

    private ResultActions getDisclosure(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/disclosure", observationId));
    }

    private ResultActions getAddenda(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/disclosure/addenda", observationId));
    }

    private ResultActions getDiagnostics(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/diagnostics", observationId));
    }

    // ---------- 禁运设置与可见性 ----------

    @Test
    void embargoHidesContentFromPublicQueryButEvidenceVisible() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-e1", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC", "INTERNAL"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.items[0].embargoUntilUtc").value(T1_TEXT))
                .andExpect(jsonPath("$.items[0].scope[0]").value("INTERNAL"))
                .andExpect(jsonPath("$.items[0].scope[1]").value("PUBLIC"))
                .andExpect(jsonPath("$.items[0].setAtUtc").value("2026-01-01T00:00:00Z"));

        // 截止前公开查询不返回簇内容
        getPublic("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoed").value(true))
                .andExpect(jsonPath("$.embargoUntilUtc").value(T1_TEXT))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
        // 授权证据查询可见；非授权操作者 403
        getEvidence("obs-1", "ops")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.version").value(1));
        getEvidence("obs-1", "intruder").andExpect(status().isForbidden());

        getDiagnostics("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoed").value(true))
                .andExpect(jsonPath("$.embargoSet").value(true))
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.openConflictCount").value(0));
        getDisclosure("obs-1").andExpect(status().isNotFound());

        // 左闭右开：到达截止时刻禁运即失效，公开查询恢复内容
        now.set(T1);
        getPublic("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.embargoed").doesNotExist());
    }

    @Test
    void embargoUntilBeforeClusterCreationReturns422() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-e2", "ops", List.of(item("obs-1", "2025-12-31T23:59:59Z", List.of("PUBLIC"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("actual=2025-12-31T23:59:59Z")))
                .andExpect(jsonPath("$.message", containsString("required>=2026-01-01T00:00:00Z")));
        // 未写入禁运；失败不占键，同一 requestId 修正参数后可重试
        getDiagnostics("obs-1").andExpect(jsonPath("$.embargoSet").value(false));
        setEmbargo("req-e2", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isCreated());
    }

    @Test
    void emptyScopeReturns422() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-e3", "ops", List.of(item("obs-1", T1_TEXT, List.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("scope")));
        getDiagnostics("obs-1").andExpect(jsonPath("$.embargoSet").value(false));
    }

    @Test
    void unresolvedConflictBlocksEmbargoUntilResolved() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-m1", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 离线端基于版本 1 改地点为站点C：与站点B冲突，登记未解决冲突
        merge("req-m2", "obs-1", 1, "站点C", "1.0", "备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"));

        getDiagnostics("obs-1")
                .andExpect(jsonPath("$.openConflictCount").value(1))
                .andExpect(jsonPath("$.openConflicts[0].conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.openConflicts[0].baseVersion").value(1))
                .andExpect(jsonPath("$.openConflicts[0].currentVersion").value(2));

        setEmbargo("req-e4", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("unresolved conflicts")))
                .andExpect(jsonPath("$.message", containsString("location")));
        getDiagnostics("obs-1").andExpect(jsonPath("$.embargoSet").value(false));

        // 显式解决后未解决冲突清除，禁运可设置
        resolve("req-s1", "res-1", "obs-1", 1, 2, "站点C", Map.of("location", "CANDIDATE"), "ops")
                .andExpect(status().isOk());
        getDiagnostics("obs-1").andExpect(jsonPath("$.openConflictCount").value(0));
        setEmbargo("req-e4", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isCreated());
    }

    // ---------- 批量原子性 ----------

    @Test
    void batchSetEmbargoValidatesAllThenCommitsAtomically() throws Exception {
        create("req-ca", "obs-a", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-cb", "obs-b", "站点B", "2.0", "备注").andExpect(status().isCreated());

        // obs-b 截止时刻早于簇创建：整批 422，obs-a 同样未生效
        setEmbargo("req-b1", "ops", List.of(
                item("obs-a", T1_TEXT, List.of("PUBLIC")),
                item("obs-b", "2025-12-31T23:59:59Z", List.of("PUBLIC"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("obs-b")));
        getDiagnostics("obs-a").andExpect(jsonPath("$.embargoSet").value(false));
        getDiagnostics("obs-b").andExpect(jsonPath("$.embargoSet").value(false));

        // 失败不占键：同一 requestId 修正参数后整批生效，响应按 observationId 稳定排序
        setEmbargo("req-b1", "ops", List.of(
                item("obs-b", T1_TEXT, List.of("PUBLIC")),
                item("obs-a", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.items[1].observationId").value("obs-b"));
        getDiagnostics("obs-a").andExpect(jsonPath("$.embargoSet").value(true));
        getDiagnostics("obs-b").andExpect(jsonPath("$.embargoSet").value(true));
    }

    @Test
    void batchSetEmbargoWithMissingObservationReturns404AndWritesNothing() throws Exception {
        create("req-cc", "obs-c", "站点C", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-b2", "ops", List.of(
                item("obs-c", T1_TEXT, List.of("PUBLIC")),
                item("obs-x", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("obs-x")));
        getDiagnostics("obs-c").andExpect(jsonPath("$.embargoSet").value(false));
    }

    @Test
    void duplicateObservationIdInBatchReturns400() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-b3", "ops", List.of(
                item("obs-1", T1_TEXT, List.of("PUBLIC")),
                item("obs-1", T1_TEXT, List.of("INTERNAL"))))
                .andExpect(status().isBadRequest());
        getDiagnostics("obs-1").andExpect(jsonPath("$.embargoSet").value(false));
    }

    // ---------- 禁运不可变与幂等 ----------

    @Test
    void embargoIsImmutableAndRequestIdIdempotent() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-e1", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isCreated());
        // 同键同参重放：返回首次完整响应快照
        setEmbargo("req-e1", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.items[0].setAtUtc").value("2026-01-01T00:00:00Z"));
        // 同键异参 409（改截止时刻或改操作者）
        setEmbargo("req-e1", "ops", List.of(item("obs-1", "2026-01-01T02:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isConflict());
        setEmbargo("req-e1", "other", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isConflict());
        // 禁运不可变：新 requestId 重复设置 409
        setEmbargo("req-e2", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("embargo already set")));
        // 禁运设置未被改写
        getDiagnostics("obs-1")
                .andExpect(jsonPath("$.embargoUntilUtc").value(T1_TEXT))
                .andExpect(jsonPath("$.scope[0]").value("PUBLIC"));
    }

    // ---------- 到期发布 ----------

    @Test
    void releaseBeforeExpiryReturns422WithRemainingAndNotOccupyingKey() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-e1", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isCreated());

        now.set(T0.plusSeconds(1800));
        release("req-r1", "obs-1", "ops")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("remainingSeconds=1800")))
                .andExpect(jsonPath("$.message", containsString("required>=" + T1_TEXT)));
        getDiagnostics("obs-1").andExpect(jsonPath("$.released").value(false));

        // 失败不占键：到期后同一 requestId 发布成功
        now.set(T1);
        release("req-r1", "obs-1", "ops")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.winningVersion").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.releasedAtUtc").value(T1_TEXT))
                .andExpect(jsonPath("$.embargoUntilUtc").value(T1_TEXT))
                .andExpect(jsonPath("$.conflictResolutions").isEmpty())
                .andExpect(jsonPath("$.qualityFlags").isEmpty());
        // 同键同参重放返回首次完整响应快照
        release("req-r1", "obs-1", "ops")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.winningVersion").value(1));
        // 同键异参 409
        release("req-r1", "obs-1", "other").andExpect(status().isConflict());
        // 已发布：新 requestId 返回同一不可变快照
        release("req-r2", "obs-1", "ops")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningVersion").value(1))
                .andExpect(jsonPath("$.releasedAtUtc").value(T1_TEXT));
    }

    @Test
    void releaseSnapshotIsImmutableAndCorrectionsAppendAddenda() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-m1", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        merge("req-m2", "obs-1", 1, "站点C", "1.0", "备注").andExpect(status().isConflict());
        resolve("req-s1", "res-1", "obs-1", 1, 2, "站点C", Map.of("location", "CANDIDATE"), "ops")
                .andExpect(status().isOk());
        setEmbargo("req-e1", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isCreated());

        now.set(T1);
        release("req-r1", "obs-1", "ops")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.winningVersion").value(3))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.conflictResolutions[0].resolutionId").value("res-1"))
                .andExpect(jsonPath("$.conflictResolutions[0].conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.conflictResolutions[0].selections.location").value("CANDIDATE"))
                .andExpect(jsonPath("$.qualityFlags[0]").value("HAS_RESOLVED_CONFLICTS"));
        getDisclosure("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningVersion").value(3))
                .andExpect(jsonPath("$.location").value("站点C"));

        // 到期后的新更正只进入附页，不改写首次快照
        merge("req-m3", "obs-1", 3, "站点D", "2.0", "更正备注").andExpect(status().isOk());
        getDisclosure("obs-1")
                .andExpect(jsonPath("$.winningVersion").value(3))
                .andExpect(jsonPath("$.location").value("站点C"));
        getAddenda("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].version").value(4))
                .andExpect(jsonPath("$[0].location").value("站点D"))
                .andExpect(jsonPath("$[0].requestId").value("req-m3"))
                .andExpect(jsonPath("$[0].createdAtUtc").value(T1_TEXT));

        // 删除墓碑同样只追加附页
        delete("req-d1", "obs-1", 4).andExpect(status().isOk());
        getAddenda("obs-1")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].seq").value(2))
                .andExpect(jsonPath("$[1].version").value(5))
                .andExpect(jsonPath("$[1].deleted").value(true))
                .andExpect(jsonPath("$[1].location").doesNotExist());
        getDisclosure("obs-1")
                .andExpect(jsonPath("$.winningVersion").value(3))
                .andExpect(jsonPath("$.deleted").value(false));
        getDiagnostics("obs-1")
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.embargoed").value(false))
                .andExpect(jsonPath("$.releasedAtUtc").value(T1_TEXT));
    }

    // ---------- 缺失与墓碑分支 ----------

    @Test
    void embargoAndReleaseOnMissingOrDeletedObservation() throws Exception {
        setEmbargo("req-e1", "ops", List.of(item("obs-x", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isNotFound());
        release("req-r1", "obs-x", "ops").andExpect(status().isNotFound());

        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        // 未设置禁运直接发布：404
        release("req-r2", "obs-1", "ops").andExpect(status().isNotFound());
        // 已删除记录设置禁运：410
        delete("req-d1", "obs-1", 1).andExpect(status().isOk());
        setEmbargo("req-e2", "ops", List.of(item("obs-1", T1_TEXT, List.of("PUBLIC"))))
                .andExpect(status().isGone());
        getDiagnostics("obs-1").andExpect(jsonPath("$.embargoSet").value(false));
        getDiagnostics("obs-x").andExpect(status().isNotFound());
    }
}
