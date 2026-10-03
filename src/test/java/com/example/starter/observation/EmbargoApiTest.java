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
 * 观测禁运与到期发布 API 测试：主流程、422 校验分支、批量回滚、不可变性、
 * 幂等重放、发布后更正附页与诊断查询。真实 H2 内存库（MODE=MySQL）与可控 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmbargoApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2027-06-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2027-06-01T01:00:00Z");
    private static final Instant AFTER_EXPIRY = Instant.parse("2027-06-01T02:00:00Z");
    private static final String EVIDENCE_KEY = "local-evidence-key";

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
        jdbcTemplate.update("DELETE FROM disclosure_appendix");
        jdbcTemplate.update("DELETE FROM disclosure_snapshot");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM observation_pending_conflict");
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

    private ResultActions resolve(String requestId, String observationId, String resolutionId,
                                  int baseVersion, int expectedCurrentVersion,
                                  String location, String reading, String note,
                                  Map<String, String> selections) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        body.put("selections", selections);
        body.put("operator", "tester");
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> item(String observationId, String embargoUntilUtc, String disclosureScope) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", observationId);
        item.put("embargoUntilUtc", embargoUntilUtc);
        item.put("disclosureScope", disclosureScope);
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

    private ResultActions release(String requestId, String observationId, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/release", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions correct(String requestId, String observationId, String operator,
                                  String location, String reading, String note, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/observations/{id}/corrections", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void advanceClockTo(Instant instant) {
        Mockito.when(clock.instant()).thenReturn(instant);
    }

    private int rowCount(String table, String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }
    // ---------- 主流程 ----------

    @Test
    void embargoHidesPublicContentUntilRelease() throws Exception {
        create("req-e0", "obs-e1", "站点A", "12.5", "初始备注").andExpect(status().isCreated());

        setEmbargo("req-e1", "op-1", List.of(item("obs-e1", UNTIL.toString(), "PUBLIC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("req-e1"))
                .andExpect(jsonPath("$.setAtUtc").value(FIXED_NOW.toString()))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-e1"))
                .andExpect(jsonPath("$.items[0].embargoUntilUtc").value(UNTIL.toString()))
                .andExpect(jsonPath("$.items[0].disclosureScope").value("PUBLIC"))
                .andExpect(jsonPath("$.items[0].released").value(false));

        // 截止前：公开查询不返回内容；证据查询需授权密钥
        mockMvc.perform(get("/api/observations/obs-e1/public"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("EMBARGOED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("remainingMillis=3600000")));
        mockMvc.perform(get("/api/observations/obs-e1/evidence"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("EVIDENCE_UNAUTHORIZED"));
        mockMvc.perform(get("/api/observations/obs-e1/evidence").header("X-Evidence-Key", "wrong"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/observations/obs-e1/evidence").header("X-Evidence-Key", EVIDENCE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.embargoUntilUtc").value(UNTIL.toString()))
                .andExpect(jsonPath("$.pendingConflict").value(false));

        mockMvc.perform(get("/api/observations/obs-e1/embargo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoUntilUtc").value(UNTIL.toString()))
                .andExpect(jsonPath("$.released").value(false));
        mockMvc.perform(get("/api/observations/obs-e1/embargo-diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoSet").value(true))
                .andExpect(jsonPath("$.expired").value(false))
                .andExpect(jsonPath("$.remainingMillis").value(3600000))
                .andExpect(jsonPath("$.appendixCount").value(0));
        mockMvc.perform(get("/api/observations/obs-e1/disclosure"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("NOT_RELEASED"));

        // 到期后、发布前：公开查询仍不返回内容
        advanceClockTo(AFTER_EXPIRY);
        mockMvc.perform(get("/api/observations/obs-e1/embargo-diagnostics"))
                .andExpect(jsonPath("$.expired").value(true))
                .andExpect(jsonPath("$.remainingMillis").value(0));
        mockMvc.perform(get("/api/observations/obs-e1/public"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("RELEASE_PENDING"));

        release("req-e2", "obs-e1", "op-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.qualityFlags[0]").value("CLEAN"))
                .andExpect(jsonPath("$.releasedAtUtc").value(AFTER_EXPIRY.toString()))
                .andExpect(jsonPath("$.appendixCount").value(0));

        mockMvc.perform(get("/api/observations/obs-e1/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.releasedAtUtc").value(AFTER_EXPIRY.toString()))
                .andExpect(jsonPath("$.appendices.length()").value(0));
        mockMvc.perform(get("/api/observations/obs-e1/embargo"))
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.releasedAtUtc").value(AFTER_EXPIRY.toString()));
    }

    @Test
    void publicQueryWithoutEmbargoReturnsCurrentContent() throws Exception {
        create("req-p0", "obs-p1", "站点P", "3.14", "公开备注").andExpect(status().isCreated());
        mockMvc.perform(get("/api/observations/obs-p1/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("站点P"))
                .andExpect(jsonPath("$.appendices.length()").value(0));
    }

    // ---------- 422 校验分支 ----------

    @Test
    void setEmbargoRejectsUntilBeforeCreation() throws Exception {
        create("req-b0", "obs-b1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-b1", "op-1", List.of(item("obs-b1", "2020-01-01T00:00:00Z", "PUBLIC")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("EMBARGO_BEFORE_CREATION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("required>=")));
        assertThat(rowCount("observation_embargo", "obs-b1")).isZero();
    }

    @Test
    void setEmbargoRejectsBlankScope() throws Exception {
        create("req-s0", "obs-s1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-s1", "op-1", List.of(item("obs-s1", UNTIL.toString(), "   ")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("EMPTY_DISCLOSURE_SCOPE"));
        assertThat(rowCount("observation_embargo", "obs-s1")).isZero();
    }

    @Test
    void setEmbargoRejectsUnresolvedConflictThenSucceedsAfterResolve() throws Exception {
        create("req-u0", "obs-u1", "站点A", "1.0", "备注A").andExpect(status().isCreated());
        merge("req-u1", "obs-u1", 1, "站点B", "1.0", "备注A").andExpect(status().isOk());
        merge("req-u2", "obs-u1", 1, "站点C", "1.0", "备注A")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"));

        setEmbargo("req-u3", "op-1", List.of(item("obs-u1", UNTIL.toString(), "PUBLIC")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("UNRESOLVED_CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("location")));

        // 证据查询可见未解决冲突标记
        mockMvc.perform(get("/api/observations/obs-u1/evidence").header("X-Evidence-Key", EVIDENCE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingConflict").value(true))
                .andExpect(jsonPath("$.conflictFields[0]").value("location"));

        resolve("req-u4", "obs-u1", "res-u1", 1, 2, "站点C", "1.0", "备注A",
                Map.of("location", "CANDIDATE")).andExpect(status().isOk());

        setEmbargo("req-u5", "op-1", List.of(item("obs-u1", UNTIL.toString(), "PUBLIC")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/observations/obs-u1/evidence").header("X-Evidence-Key", EVIDENCE_KEY))
                .andExpect(jsonPath("$.pendingConflict").value(false));
    }

    @Test
    void batchSetEmbargoRollsBackEntirelyOnAnyInvalidItem() throws Exception {
        create("req-r0", "obs-r1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-r1", "obs-r2", "站点B", "2.0", "备注").andExpect(status().isCreated());

        setEmbargo("req-r2", "op-1", List.of(
                        item("obs-r1", UNTIL.toString(), "PUBLIC"),
                        item("obs-r2", UNTIL.toString(), "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("EMPTY_DISCLOSURE_SCOPE"));

        // 整次回滚：合法簇也未写入；失败不占 requestId 键
        assertThat(rowCount("observation_embargo", "obs-r1")).isZero();
        assertThat(rowCount("observation_embargo", "obs-r2")).isZero();
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-r2'", Integer.class);
        assertThat(logCount).isZero();

        setEmbargo("req-r2", "op-1", List.of(
                        item("obs-r1", UNTIL.toString(), "PUBLIC"),
                        item("obs-r2", UNTIL.toString(), "INTERNAL")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].observationId").value("obs-r1"))
                .andExpect(jsonPath("$.items[1].observationId").value("obs-r2"));
    }
    // ---------- 不可变与幂等 ----------

    @Test
    void embargoIsImmutableAndRequestIdempotent() throws Exception {
        create("req-i0", "obs-i1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-i1", "op-1", List.of(item("obs-i1", UNTIL.toString(), "PUBLIC")))
                .andExpect(status().isCreated());

        // 禁运不可变：换个 requestId 重复设置同簇 409
        setEmbargo("req-i2", "op-1", List.of(item("obs-i1", UNTIL.toString(), "INTERNAL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("EMBARGO_ALREADY_SET"));

        // 同参重放：返回首次完整响应快照
        setEmbargo("req-i1", "op-1", List.of(item("obs-i1", UNTIL.toString(), "PUBLIC")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.setAtUtc").value(FIXED_NOW.toString()))
                .andExpect(jsonPath("$.items[0].disclosureScope").value("PUBLIC"));
        // 同键异参 409
        setEmbargo("req-i1", "op-1", List.of(item("obs-i1", UNTIL.toString(), "INTERNAL")))
                .andExpect(status().isConflict());
        assertThat(rowCount("observation_embargo", "obs-i1")).isEqualTo(1);
    }

    @Test
    void releaseValidationsAndFailureNotOccupyingKey() throws Exception {
        create("req-v0", "obs-v1", "站点A", "1.0", "备注").andExpect(status().isCreated());

        release("req-v1", "obs-v1", "op-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("EMBARGO_NOT_SET"));

        setEmbargo("req-v2", "op-1", List.of(item("obs-v1", UNTIL.toString(), "PUBLIC")))
                .andExpect(status().isCreated());
        release("req-v3", "obs-v1", "op-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("EMBARGO_NOT_EXPIRED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("remainingMillis=3600000")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("requiredMillis=0")));

        // 失败不占键：到期后同一 requestId 可正常发布
        advanceClockTo(AFTER_EXPIRY);
        release("req-v3", "obs-v1", "op-1").andExpect(status().isCreated());
        assertThat(rowCount("disclosure_snapshot", "obs-v1")).isEqualTo(1);
    }

    @Test
    void releaseIsIdempotentAndSingleSnapshot() throws Exception {
        create("req-w0", "obs-w1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-w1", "op-1", List.of(item("obs-w1", FIXED_NOW.toString(), "PUBLIC")))
                .andExpect(status().isCreated());

        release("req-w2", "obs-w1", "op-1").andExpect(status().isCreated());
        // 同参重放返回首次响应
        release("req-w2", "obs-w1", "op-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.releasedAtUtc").value(FIXED_NOW.toString()));
        // 同键异参 409
        release("req-w2", "obs-w1", "op-2").andExpect(status().isConflict());
        // 换 requestId 重复发布：返回既有快照（200），快照仍只有一行
        release("req-w3", "obs-w1", "op-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAtUtc").value(FIXED_NOW.toString()))
                .andExpect(jsonPath("$.operator").value("op-1"));
        assertThat(rowCount("disclosure_snapshot", "obs-w1")).isEqualTo(1);
    }

    // ---------- 快照不可变与更正附页 ----------

    @Test
    void correctionsAppendWithoutRewritingSnapshot() throws Exception {
        create("req-c0", "obs-c1", "站点A", "1.0", "备注A").andExpect(status().isCreated());
        setEmbargo("req-c1", "op-1", List.of(item("obs-c1", FIXED_NOW.toString(), "PUBLIC")))
                .andExpect(status().isCreated());

        // 未发布时更正 409
        create("req-cx", "obs-c2", "站点B", "2.0", "备注B").andExpect(status().isCreated());
        correct("req-c9", "obs-c2", "op-1", "站点X", "9.9", "备注X", "复核")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("NOT_RELEASED"));

        release("req-c2", "obs-c1", "op-1").andExpect(status().isCreated());
        correct("req-c3", "obs-c1", "op-1", "站点X", "2.5", "备注X", "现场复核")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.location").value("站点X"))
                .andExpect(jsonPath("$.createdAtUtc").value(FIXED_NOW.toString()));
        correct("req-c4", "obs-c1", "op-2", "站点Y", "3.5", "备注Y", "二次复核")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(2));

        // 首次快照不被改写，附页按 seq 升序
        mockMvc.perform(get("/api/observations/obs-c1/disclosure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.appendixCount").value(2))
                .andExpect(jsonPath("$.appendices[0].seq").value(1))
                .andExpect(jsonPath("$.appendices[0].location").value("站点X"))
                .andExpect(jsonPath("$.appendices[1].seq").value(2))
                .andExpect(jsonPath("$.appendices[1].location").value("站点Y"));
        mockMvc.perform(get("/api/observations/obs-c1/public"))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.appendices.length()").value(2));

        // 更正幂等：同参重放不新增附页，异参 409
        correct("req-c3", "obs-c1", "op-1", "站点X", "2.5", "备注X", "现场复核")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1));
        correct("req-c3", "obs-c1", "op-1", "站点X", "2.5", "备注X", "其他原因")
                .andExpect(status().isConflict());
        assertThat(rowCount("disclosure_appendix", "obs-c1")).isEqualTo(2);
    }

    @Test
    void releaseSnapshotIncludesConflictAdjudication() throws Exception {
        create("req-a0", "obs-a1", "站点A", "1.0", "备注A").andExpect(status().isCreated());
        merge("req-a1", "obs-a1", 1, "站点B", "1.0", "备注A").andExpect(status().isOk());
        merge("req-a2", "obs-a1", 1, "站点C", "1.0", "备注A").andExpect(status().isConflict());
        resolve("req-a3", "obs-a1", "res-a1", 1, 2, "站点C", "1.0", "备注A",
                Map.of("location", "CANDIDATE")).andExpect(status().isOk());

        setEmbargo("req-a4", "op-1", List.of(item("obs-a1", FIXED_NOW.toString(), "PUBLIC")))
                .andExpect(status().isCreated());
        release("req-a5", "obs-a1", "op-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.resolutionId").value("res-a1"))
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.selections.location").value("CANDIDATE"))
                .andExpect(jsonPath("$.qualityFlags[0]").value("CONFLICT_RESOLVED"));
    }
}