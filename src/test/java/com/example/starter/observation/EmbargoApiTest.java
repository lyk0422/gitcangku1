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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测禁运与到期发布 API 测试：设置校验（422/404/410/409）、批量回滚、幂等重放、
 * 截止前公开查询不返回内容、到期首次发布生成不可变快照、发布后更正进入附页。
 * 使用真实 H2 内存库与可控 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmbargoApiTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM embargo_release_addendum");
        jdbcTemplate.update("DELETE FROM embargo_release");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM observation_open_conflict");
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

    private ResultActions deleteObs(String requestId, String observationId, int expectedVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> item(String observationId, Instant embargoUntilUtc, List<String> scope) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", observationId);
        item.put("embargoUntilUtc", embargoUntilUtc.toString());
        item.put("disclosureScope", scope);
        return item;
    }

    private ResultActions setEmbargo(String requestId, String operator,
                                     List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/embargoes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions publicGet(String observationId) throws Exception {
        return mockMvc.perform(get("/api/public/observations/{id}", observationId));
    }

    private ResultActions getEmbargo(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/embargo", observationId));
    }

    private ResultActions getRelease(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/release", observationId));
    }

    private int countRows(String table, String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    @Test
    void embargoLifecyclePublicReleaseAndImmutableSnapshot() throws Exception {
        create("req-c1", "obs-e1", "站点A", "1.0", "备注").andExpect(status().isCreated());

        setEmbargo("req-e1", "operator-a",
                List.of(item("obs-e1", T0.plusSeconds(3600), List.of("PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedCount").value(1))
                .andExpect(jsonPath("$.appliedCount").value(1))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-e1"))
                .andExpect(jsonPath("$.items[0].disclosureScope[0]").value("PUBLIC"))
                .andExpect(jsonPath("$.items[0].versionAtSet").value(1));

        // 截止前：公开查询不返回簇内容，返回实际剩余秒数
        now.set(T0.plusSeconds(1000));
        publicGet("obs-e1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMBARGOED"))
                .andExpect(jsonPath("$.remainingSeconds").value(2600))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.release").doesNotExist());

        // 授权证据查询在截止前可见内容
        mockMvc.perform(get("/api/observations/{id}", "obs-e1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"));

        // 左闭右开：截止前 1 秒仍禁运
        now.set(T0.plusSeconds(3599));
        publicGet("obs-e1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMBARGOED"))
                .andExpect(jsonPath("$.remainingSeconds").value(1));

        // 到达截止时刻：首次公开发布生成不可变快照
        now.set(T0.plusSeconds(3600));
        publicGet("obs-e1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.release.releaseId").value("rel-obs-e1"))
                .andExpect(jsonPath("$.release.releasedVersion").value(1))
                .andExpect(jsonPath("$.release.location").value("站点A"))
                .andExpect(jsonPath("$.release.qualityFlags[0]").value("CLEAN"))
                .andExpect(jsonPath("$.release.releasedAtUtc").value("2026-01-01T01:00:00Z"));

        // 之后读取不改变状态，仍返回首次快照
        now.set(T0.plusSeconds(7200));
        publicGet("obs-e1").andExpect(status().isOk())
                .andExpect(jsonPath("$.release.releasedVersion").value(1))
                .andExpect(jsonPath("$.release.releasedAtUtc").value("2026-01-01T01:00:00Z"));
        assertThat(countRows("embargo_release", "obs-e1")).isEqualTo(1);

        // 禁运明细状态流转为 RELEASED
        getEmbargo("obs-e1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releaseId").value("rel-obs-e1"))
                .andExpect(jsonPath("$.operator").value("operator-a"));
    }

    @Test
    void setEmbargoRejectsInvalidItemsAndFailureDoesNotOccupyKey() throws Exception {
        create("req-c2", "obs-v1", "站点A", "1.0", "备注").andExpect(status().isCreated());

        // 截止时刻早于簇创建（簇创建于 T0）：422，消息携带差额秒数
        setEmbargo("req-v1", "op", List.of(item("obs-v1", T0.minusSeconds(1), List.of("PUBLIC"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("shortfall 1 seconds")));

        // 披露范围为空：422，消息携带实际数量与要求数量
        setEmbargo("req-v2", "op", List.of(item("obs-v1", T0.plusSeconds(10), List.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("actual size 0, required at least 1")));

        // 簇存在未解决冲突：422；解决后（任意成功写入清除冲突状态）可设置
        merge("req-v3", "obs-v1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        merge("req-v4", "obs-v1", 1, "站点C", "1.0", "备注").andExpect(status().isConflict());
        setEmbargo("req-v5", "op", List.of(item("obs-v1", T0.plusSeconds(10), List.of("PUBLIC"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("unresolved conflict")));
        resolve("req-v6", "res-v1", "obs-v1", 1, 2, "站点C", "1.0", "备注",
                Map.of("location", "CANDIDATE"), "op").andExpect(status().isOk());
        setEmbargo("req-v7", "op", List.of(item("obs-v1", T0.plusSeconds(10), List.of("PUBLIC"))))
                .andExpect(status().isCreated());

        // 不存在的簇：404；已删除的簇：410
        setEmbargo("req-v8", "op", List.of(item("obs-missing", T0.plusSeconds(10), List.of("PUBLIC"))))
                .andExpect(status().isNotFound());
        create("req-c3", "obs-v2", "站点A", "1.0", "备注").andExpect(status().isCreated());
        deleteObs("req-v9", "obs-v2", 1).andExpect(status().isOk());
        setEmbargo("req-v10", "op", List.of(item("obs-v2", T0.plusSeconds(10), List.of("PUBLIC"))))
                .andExpect(status().isGone());

        // 失败不占键：上述失败请求的 requestId 均未落库
        Integer failedKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN "
                        + "('req-v1','req-v2','req-v5','req-v8','req-v10')", Integer.class);
        assertThat(failedKeys).isZero();

        // 同一 requestId 再次失败仍不占键；修正参数（换到合法的新簇）后可成功
        setEmbargo("req-v1", "op", List.of(item("obs-v2", T0.plusSeconds(10), List.of("PUBLIC"))))
                .andExpect(status().isGone());
        create("req-c9", "obs-v3", "站点C", "3.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-v1", "op", List.of(item("obs-v3", T0.plusSeconds(10), List.of("PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appliedCount").value(1));
    }

    @Test
    void batchSetEmbargoValidatesAllThenRollsBackEntirely() throws Exception {
        create("req-c4", "obs-b1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-c5", "obs-b2", "站点B", "2.0", "备注").andExpect(status().isCreated());

        // 批次中 obs-b2 范围为空：整次 422，obs-b1 不留半成品
        setEmbargo("req-b1", "op", List.of(
                item("obs-b1", T0.plusSeconds(100), List.of("PUBLIC")),
                item("obs-b2", T0.plusSeconds(100), List.of())))
                .andExpect(status().isUnprocessableEntity());
        getEmbargo("obs-b1").andExpect(status().isNotFound());
        getEmbargo("obs-b2").andExpect(status().isNotFound());
        assertThat(countRows("observation_embargo", "obs-b1")).isZero();
        assertThat(countRows("observation_embargo", "obs-b2")).isZero();

        // 失败不占键：同一 requestId 修正参数后整批成功
        setEmbargo("req-b1", "op", List.of(
                item("obs-b1", T0.plusSeconds(100), List.of("PUBLIC")),
                item("obs-b2", T0.plusSeconds(100), List.of("AUDIT"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedCount").value(2))
                .andExpect(jsonPath("$.appliedCount").value(2))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-b1"))
                .andExpect(jsonPath("$.items[1].observationId").value("obs-b2"));

        // 诊断列表按 observationId 字典序稳定排序
        mockMvc.perform(get("/api/observations/embargoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].observationId").value("obs-b1"))
                .andExpect(jsonPath("$[1].observationId").value("obs-b2"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));
    }

    @Test
    void setEmbargoIdempotencyReplayAndImmutability() throws Exception {
        create("req-c6", "obs-i1", "站点A", "1.0", "备注").andExpect(status().isCreated());

        // 范围规范化：去重并按字典序排序
        String firstBody = setEmbargo("req-i1", "operator-a",
                List.of(item("obs-i1", T0.plusSeconds(100), List.of("PUBLIC", "AUDIT", "PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].disclosureScope[0]").value("AUDIT"))
                .andExpect(jsonPath("$.items[0].disclosureScope[1]").value("PUBLIC"))
                .andReturn().getResponse().getContentAsString();

        // 同参重放：返回首次完整响应快照（原文一致）
        String replayBody = setEmbargo("req-i1", "operator-a",
                List.of(item("obs-i1", T0.plusSeconds(100), List.of("PUBLIC", "AUDIT", "PUBLIC"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(replayBody).isEqualTo(firstBody);

        // 同键异参：409
        setEmbargo("req-i1", "operator-a",
                List.of(item("obs-i1", T0.plusSeconds(200), List.of("PUBLIC"))))
                .andExpect(status().isConflict());

        // 禁运设置不可变：新 requestId 重复设置同簇返回 409
        setEmbargo("req-i2", "operator-b",
                List.of(item("obs-i1", T0.plusSeconds(100), List.of("PUBLIC"))))
                .andExpect(status().isConflict());
        assertThat(countRows("observation_embargo", "obs-i1")).isEqualTo(1);
    }

    @Test
    void releaseSnapshotImmutableAndCorrectionsAppendAddenda() throws Exception {
        create("req-c7", "obs-r1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r1", "obs-r1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        setEmbargo("req-r2", "op", List.of(item("obs-r1", T0.plusSeconds(100), List.of("PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].versionAtSet").value(2));

        // 设禁运后、发布前的更正进入胜出版本，并体现在质量标记中
        merge("req-r3", "obs-r1", 2, "站点C", "1.5", "备注").andExpect(status().isOk());
        now.set(T0.plusSeconds(100));
        publicGet("obs-r1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.release.releasedVersion").value(3))
                .andExpect(jsonPath("$.release.location").value("站点C"))
                .andExpect(jsonPath("$.release.qualityFlags[0]").value("CORRECTED_AFTER_EMBARGO"));

        // 发布后的更正只进入附页，不改写首次快照
        merge("req-r4", "obs-r1", 3, "站点D", "2.5", "备注2").andExpect(status().isOk());
        publicGet("obs-r1").andExpect(status().isOk())
                .andExpect(jsonPath("$.release.releasedVersion").value(3))
                .andExpect(jsonPath("$.release.location").value("站点C"));

        // 授权发布详情：首次快照 + 附页（按版本升序）
        getRelease("obs-r1").andExpect(status().isOk())
                .andExpect(jsonPath("$.release.releasedVersion").value(3))
                .andExpect(jsonPath("$.addenda.length()").value(1))
                .andExpect(jsonPath("$.addenda[0].version").value(4))
                .andExpect(jsonPath("$.addenda[0].operation").value("MERGE"))
                .andExpect(jsonPath("$.addenda[0].location").value("站点D"))
                .andExpect(jsonPath("$.addenda[0].requestId").value("req-r4"));

        // 公开附页查询同样可见
        mockMvc.perform(get("/api/public/observations/{id}/addenda", "obs-r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(4));

        // 发布后的删除（墓碑）也生成附页；首次快照仍保持 v3
        deleteObs("req-r5", "obs-r1", 4).andExpect(status().isOk());
        getRelease("obs-r1").andExpect(status().isOk())
                .andExpect(jsonPath("$.release.releasedVersion").value(3))
                .andExpect(jsonPath("$.release.deleted").value(false))
                .andExpect(jsonPath("$.addenda.length()").value(2))
                .andExpect(jsonPath("$.addenda[1].version").value(5))
                .andExpect(jsonPath("$.addenda[1].operation").value("DELETE"))
                .andExpect(jsonPath("$.addenda[1].deleted").value(true))
                .andExpect(jsonPath("$.addenda[1].location").doesNotExist());
        assertThat(countRows("embargo_release", "obs-r1")).isEqualTo(1);
        assertThat(countRows("embargo_release_addendum", "obs-r1")).isEqualTo(2);
    }

    @Test
    void releaseSnapshotContainsConflictResolutionsAndQualityFlags() throws Exception {
        create("req-c8", "obs-s1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-s1", "obs-s1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        merge("req-s2", "obs-s1", 1, "站点C", "1.0", "备注").andExpect(status().isConflict());
        resolve("req-s3", "res-s1", "obs-s1", 1, 2, "站点C", "1.0", "备注",
                Map.of("location", "CANDIDATE"), "operator-r").andExpect(status().isOk());
        setEmbargo("req-s4", "op", List.of(item("obs-s1", T0.plusSeconds(100), List.of("PUBLIC"))))
                .andExpect(status().isCreated());

        now.set(T0.plusSeconds(100));
        publicGet("obs-s1").andExpect(status().isOk())
                .andExpect(jsonPath("$.release.releasedVersion").value(3))
                .andExpect(jsonPath("$.release.location").value("站点C"))
                .andExpect(jsonPath("$.release.conflictResolutions.length()").value(1))
                .andExpect(jsonPath("$.release.conflictResolutions[0].resolutionId").value("res-s1"))
                .andExpect(jsonPath("$.release.conflictResolutions[0].previousVersion").value(2))
                .andExpect(jsonPath("$.release.conflictResolutions[0].newVersion").value(3))
                .andExpect(jsonPath("$.release.conflictResolutions[0].operator").value("operator-r"))
                .andExpect(jsonPath("$.release.qualityFlags[0]").value("HAS_RESOLVED_CONFLICTS"));
    }

    @Test
    void releaseOfDeletedClusterDisclosesTombstone() throws Exception {
        create("req-c10", "obs-d1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-d1", "op", List.of(item("obs-d1", T0.plusSeconds(100), List.of("PUBLIC"))))
                .andExpect(status().isCreated());
        deleteObs("req-d2", "obs-d1", 1).andExpect(status().isOk());

        now.set(T0.plusSeconds(100));
        publicGet("obs-d1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.release.releasedVersion").value(2))
                .andExpect(jsonPath("$.release.deleted").value(true))
                .andExpect(jsonPath("$.release.location").doesNotExist())
                .andExpect(jsonPath("$.release.qualityFlags[0]").value("DELETED"))
                .andExpect(jsonPath("$.release.qualityFlags[1]").value("CORRECTED_AFTER_EMBARGO"));
    }

    @Test
    void publicQueryWithoutEmbargoAndMissingResources() throws Exception {
        create("req-c11", "obs-p1", "站点A", "1.0", "备注").andExpect(status().isCreated());

        // 未设禁运：公开查询直接返回当前内容
        publicGet("obs-p1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLIC"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.release").doesNotExist());

        // 不存在的簇：404；未设置/未发布时明细、发布与附页查询均为 404
        publicGet("obs-missing").andExpect(status().isNotFound());
        getEmbargo("obs-p1").andExpect(status().isNotFound());
        getRelease("obs-p1").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/public/observations/{id}/addenda", "obs-p1"))
                .andExpect(status().isNotFound());
    }
}
