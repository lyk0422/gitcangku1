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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测附件脱敏 API 测试：登记、脱敏、撤销、公开/证据/历史/诊断查询、
 * 快照引用固化、幂等与事务原子性（真实 H2 内存库，MySQL 兼容模式，可控 UTC 时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-27T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(FIXED_NOW);

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_version_attachment");
        jdbcTemplate.update("DELETE FROM attachment_redaction");
        jdbcTemplate.update("DELETE FROM observation_attachment");
        jdbcTemplate.update("DELETE FROM redaction_rule");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        now.set(FIXED_NOW);
        Mockito.when(clock.instant()).thenAnswer(invocation -> now.get());
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void advanceTo(Instant instant) {
        now.set(instant);
    }

    // ---------- 请求辅助 ----------

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

    private ResultActions mergeObservation(String requestId, String observationId, int baseVersion,
                                           String note) throws Exception {
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

    private ResultActions deleteObservation(String requestId, String observationId,
                                            int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerAttachment(String requestId, String observationId,
                                             String attachmentId, String contentSummary) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("attachmentId", attachmentId);
        body.put("contentSummary", contentSummary);
        return mockMvc.perform(post("/api/observations/{id}/attachments", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, String> item(String attachmentId, String redactedSummary) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("attachmentId", attachmentId);
        item.put("redactedSummary", redactedSummary);
        return item;
    }

    private ResultActions redact(String requestId, String observationId, String ruleVersion,
                                 String replacementNote, String role,
                                 List<Map<String, String>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("ruleVersion", ruleVersion);
        body.put("replacementNote", replacementNote);
        body.put("role", role);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/{id}/redactions", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions revoke(String requestId, String observationId, String attachmentId,
                                 int version, String role, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("role", role);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/observations/{obs}/attachments/{att}/redactions/{ver}/revoke",
                observationId, attachmentId, version)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerRule(String requestId, String ruleVersion, String description)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("ruleVersion", ruleVersion);
        body.put("description", description);
        return mockMvc.perform(post("/api/redaction-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions revokeRule(String requestId, String ruleVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        return mockMvc.perform(post("/api/redaction-rules/{ver}/revoke", ruleVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getPublicView(String observationId, String attachmentId) throws Exception {
        return mockMvc.perform(get("/api/observations/{obs}/attachments/{att}",
                observationId, attachmentId));
    }

    private ResultActions listPublicViews(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{obs}/attachments", observationId));
    }

    private ResultActions getVersionView(String observationId, String attachmentId, int version,
                                         String role) throws Exception {
        return mockMvc.perform(get("/api/observations/{obs}/attachments/{att}/versions/{ver}",
                observationId, attachmentId, version)
                .param("role", role == null ? "" : role));
    }

    private ResultActions getHistory(String observationId, String attachmentId,
                                     String from, String to, String role) throws Exception {
        var request = get("/api/observations/{obs}/attachments/{att}/history",
                observationId, attachmentId);
        if (from != null) {
            request = request.param("from", from);
        }
        if (to != null) {
            request = request.param("to", to);
        }
        if (role != null) {
            request = request.param("role", role);
        }
        return mockMvc.perform(request);
    }

    private ResultActions getDiagnostics(String observationId, String attachmentId) throws Exception {
        return mockMvc.perform(get("/api/observations/{obs}/attachments/{att}/diagnostics",
                observationId, attachmentId));
    }

    private ResultActions getFrozenReferences(String observationId, int version, String role)
            throws Exception {
        return mockMvc.perform(get("/api/observations/{obs}/versions/{ver}/attachment-references",
                observationId, version)
                .param("role", role == null ? "" : role));
    }

    private int redactionCount(String attachmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = ?",
                Integer.class, attachmentId);
        return count == null ? 0 : count;
    }

    // ---------- 附件登记 ----------

    @Test
    void registerAttachmentReturnsCreatedAndPublicViewShowsOriginal() throws Exception {
        createObservation("req-c1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-a1", "obs-1", "att-1", "原始摘要内容")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentId").value("att-1"))
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.contentSummary").value("原始摘要内容"))
                .andExpect(jsonPath("$.createdAtUtc").value("2026-09-27T08:00:00Z"));

        getPublicView("obs-1", "att-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displaySummary").value("原始摘要内容"))
                .andExpect(jsonPath("$.source").value("ORIGINAL"))
                .andExpect(jsonPath("$.redactionVersion").doesNotExist());

        listPublicViews("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$[0].source").value("ORIGINAL"));

        // 不存在的附件 → 404
        getPublicView("obs-1", "att-x").andExpect(status().isNotFound());
    }

    @Test
    void registerAttachmentFailures() throws Exception {
        // 观测不存在 → 404
        registerAttachment("req-e1", "obs-x", "att-1", "摘要")
                .andExpect(status().isNotFound());

        createObservation("req-e2", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-e3", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        // 重复登记 → 409
        registerAttachment("req-e4", "obs-1", "att-1", "其他摘要")
                .andExpect(status().isConflict());
        // 同 requestId 同参重放 → 返回首次响应
        registerAttachment("req-e3", "obs-1", "att-1", "摘要")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentSummary").value("摘要"));
        // 同 requestId 异参 → 409
        registerAttachment("req-e3", "obs-1", "att-2", "摘要")
                .andExpect(status().isConflict());

        // 墓碑观测 → 404
        deleteObservation("req-e5", "obs-1", 1).andExpect(status().isOk());
        registerAttachment("req-e6", "obs-1", "att-2", "摘要")
                .andExpect(status().isNotFound());
    }

    @Test
    void oversizedContentSummaryReturns422WithActualAndRequired() throws Exception {
        createObservation("req-s1", "obs-1").andExpect(status().isCreated());
        String oversized = "x".repeat(1025);
        registerAttachment("req-s2", "obs-1", "att-1", oversized)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.actual").value(1025))
                .andExpect(jsonPath("$.required").value(1024));
        // 失败不占键：修正长度后同一 requestId 可正常登记
        registerAttachment("req-s2", "obs-1", "att-1", "合法摘要")
                .andExpect(status().isCreated());
    }

    // ---------- 脱敏主流程 ----------

    @Test
    void redactCreatesIncrementingVersionsAndPublicViewShowsLatest() throws Exception {
        createObservation("req-r1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-r2", "obs-1", "att-1", "原始摘要一").andExpect(status().isCreated());
        registerAttachment("req-r3", "obs-1", "att-2", "原始摘要二").andExpect(status().isCreated());
        registerRule("req-r4", "rv-1", "规则一").andExpect(status().isCreated());
        registerRule("req-r5", "rv-2", "规则二").andExpect(status().isCreated());

        // 一次请求脱敏两个附件：各自生成版本 1
        redact("req-r6", "obs-1", "rv-1", "统一替换", "REDACTOR",
                List.of(item("att-1", "脱敏摘要一"), item("att-2", "脱敏摘要二")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.ruleVersion").value("rv-1"))
                .andExpect(jsonPath("$.items[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$.items[0].redactionVersion").value(1))
                .andExpect(jsonPath("$.items[0].status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.items[0].createdAtUtc").value("2026-09-27T08:00:00Z"))
                .andExpect(jsonPath("$.items[1].redactionVersion").value(1));

        getPublicView("obs-1", "att-1")
                .andExpect(jsonPath("$.displaySummary").value("脱敏摘要一"))
                .andExpect(jsonPath("$.source").value("REDACTION"))
                .andExpect(jsonPath("$.redactionVersion").value(1));

        // 第二规则版本脱敏 att-1：版本递增到 2，公开视图只返回最新有效版本
        redact("req-r7", "obs-1", "rv-2", "再次替换", "REDACTOR",
                List.of(item("att-1", "二版脱敏摘要")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].redactionVersion").value(2));
        getPublicView("obs-1", "att-1")
                .andExpect(jsonPath("$.displaySummary").value("二版脱敏摘要"))
                .andExpect(jsonPath("$.redactionVersion").value(2));
        // att-2 仍停留在版本 1
        getPublicView("obs-1", "att-2")
                .andExpect(jsonPath("$.displaySummary").value("脱敏摘要二"))
                .andExpect(jsonPath("$.redactionVersion").value(1));
        assertThat(redactionCount("att-1")).isEqualTo(2);
        assertThat(redactionCount("att-2")).isEqualTo(1);
    }

    @Test
    void redactValidatesAllAttachmentsBeforeWritingAnyVersion() throws Exception {
        createObservation("req-v1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-v2", "obs-1", "att-1", "摘要一").andExpect(status().isCreated());
        registerAttachment("req-v3", "obs-1", "att-2", "摘要二").andExpect(status().isCreated());
        registerRule("req-v4", "rv-1", "规则一").andExpect(status().isCreated());

        // 第二项附件不存在：整次失败，不产生任何脱敏版本
        redact("req-v5", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏一"), item("att-missing", "脱敏二")))
                .andExpect(status().isNotFound());
        assertThat(redactionCount("att-1")).isZero();
        assertThat(redactionCount("att-2")).isZero();
        getPublicView("obs-1", "att-1")
                .andExpect(jsonPath("$.source").value("ORIGINAL"))
                .andExpect(jsonPath("$.displaySummary").value("摘要一"));
    }

    @Test
    void redactWithUnregisteredRuleReturns422() throws Exception {
        createObservation("req-u1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-u2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        redact("req-u3", "obs-1", "rv-missing", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(redactionCount("att-1")).isZero();
    }

    @Test
    void redactWithRevokedRuleReturns422() throws Exception {
        createObservation("req-w1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-w2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-w3", "rv-1", "规则一").andExpect(status().isCreated());
        revokeRule("req-w4", "rv-1").andExpect(status().isOk());
        redact("req-w5", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(redactionCount("att-1")).isZero();
    }

    @Test
    void redactWithUnauthorizedRoleReturns403() throws Exception {
        createObservation("req-f1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-f2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-f3", "rv-1", "规则一").andExpect(status().isCreated());
        redact("req-f4", "obs-1", "rv-1", "替换", "VIEWER",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isForbidden());
        assertThat(redactionCount("att-1")).isZero();
    }

    @Test
    void redactOnTombstoneOrMissingObservationReturns404() throws Exception {
        createObservation("req-t1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-t2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-t3", "rv-1", "规则一").andExpect(status().isCreated());
        deleteObservation("req-t4", "obs-1", 1).andExpect(status().isOk());
        // 墓碑观测 → 404
        redact("req-t5", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isNotFound());
        // 观测不存在 → 404
        redact("req-t6", "obs-x", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isNotFound());
        assertThat(redactionCount("att-1")).isZero();
    }

    @Test
    void redactSameRuleVersionTwiceReturns409() throws Exception {
        createObservation("req-d1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-d2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-d3", "rv-1", "规则一").andExpect(status().isCreated());
        redact("req-d4", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isCreated());
        // 同一附件同一规则版本只允许一个脱敏版本
        redact("req-d5", "obs-1", "rv-1", "再次替换", "REDACTOR",
                List.of(item("att-1", "二版脱敏")))
                .andExpect(status().isConflict());
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    @Test
    void oversizedRedactedSummaryReturns422WithActualAndRequired() throws Exception {
        createObservation("req-o1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-o2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-o3", "rv-1", "规则一").andExpect(status().isCreated());
        String oversized = "y".repeat(1025);
        redact("req-o4", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", oversized)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.actual").value(1025))
                .andExpect(jsonPath("$.required").value(1024));
        assertThat(redactionCount("att-1")).isZero();
    }

    // ---------- 撤销 ----------

    @Test
    void revokeRemovesPublicDisplayButKeepsVersionAndReason() throws Exception {
        createObservation("req-v1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-v2", "obs-1", "att-1", "原始摘要").andExpect(status().isCreated());
        registerRule("req-v3", "rv-1", "规则一").andExpect(status().isCreated());
        redact("req-v4", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏摘要")))
                .andExpect(status().isCreated());
        getPublicView("obs-1", "att-1")
                .andExpect(jsonPath("$.displaySummary").value("脱敏摘要"))
                .andExpect(jsonPath("$.source").value("REDACTION"));

        // 撤销：仅撤销公开展示资格，保留版本、理由和撤销时刻
        revoke("req-v5", "obs-1", "att-1", 1, "REDACTOR", "过期")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAtUtc").value("2026-09-27T08:00:00Z"))
                .andExpect(jsonPath("$.revokeReason").value("过期"));

        // 公开视图回退到原始摘要
        getPublicView("obs-1", "att-1")
                .andExpect(jsonPath("$.displaySummary").value("原始摘要"))
                .andExpect(jsonPath("$.source").value("ORIGINAL"))
                .andExpect(jsonPath("$.redactionVersion").doesNotExist());

        // 证据视图仍可按版本读取脱敏版本与撤销信息
        getVersionView("obs-1", "att-1", 1, "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redactedSummary").value("脱敏摘要"))
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeReason").value("过期"));
        // 版本记录保留，不物理删除
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    @Test
    void revokeFailures() throws Exception {
        createObservation("req-x1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-x2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-x3", "rv-1", "规则一").andExpect(status().isCreated());
        redact("req-x4", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isCreated());

        // 非脱敏角色 → 403
        revoke("req-x5", "obs-1", "att-1", 1, "VIEWER", "原因")
                .andExpect(status().isForbidden());
        // 正常撤销
        revoke("req-x6", "obs-1", "att-1", 1, "REDACTOR", "过期")
                .andExpect(status().isOk());
        // 重复撤销 → 409
        revoke("req-x7", "obs-1", "att-1", 1, "REDACTOR", "再次")
                .andExpect(status().isConflict());
        // 版本不存在 → 404
        revoke("req-x8", "obs-1", "att-1", 9, "REDACTOR", "原因")
                .andExpect(status().isNotFound());
        // 附件不存在 → 404
        revoke("req-x9", "obs-1", "att-x", 1, "REDACTOR", "原因")
                .andExpect(status().isNotFound());
    }

    // ---------- 证据与历史查询 ----------

    @Test
    void evidenceViewRequiresEvidenceRoleAndReadsOriginalAndRedactedByVersion() throws Exception {
        createObservation("req-g1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-g2", "obs-1", "att-1", "原始摘要").andExpect(status().isCreated());
        registerRule("req-g3", "rv-1", "规则一").andExpect(status().isCreated());
        redact("req-g4", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏摘要")))
                .andExpect(status().isCreated());

        // 无角色 / 非证据角色 → 403
        getVersionView("obs-1", "att-1", 1, null).andExpect(status().isForbidden());
        getVersionView("obs-1", "att-1", 1, "REDACTOR").andExpect(status().isForbidden());

        // 证据角色：version 0 读原始摘要，version 1 读脱敏版本
        getVersionView("obs-1", "att-1", 0, "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.redactedSummary").value("原始摘要"))
                .andExpect(jsonPath("$.status").value("ORIGINAL"));
        getVersionView("obs-1", "att-1", 1, "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.redactedSummary").value("脱敏摘要"))
                .andExpect(jsonPath("$.replacementNote").value("替换"))
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));
        // 版本不存在 → 404
        getVersionView("obs-1", "att-1", 9, "EVIDENCE").andExpect(status().isNotFound());
    }

    @Test
    void historyReturnsEffectiveOnlyForPublicAndAllForEvidence() throws Exception {
        createObservation("req-h1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-h2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-h3", "rv-1", "规则一").andExpect(status().isCreated());
        registerRule("req-h4", "rv-2", "规则二").andExpect(status().isCreated());
        redact("req-h5", "obs-1", "rv-1", "替换一", "REDACTOR",
                List.of(item("att-1", "脱敏一")))
                .andExpect(status().isCreated());
        redact("req-h6", "obs-1", "rv-2", "替换二", "REDACTOR",
                List.of(item("att-1", "脱敏二")))
                .andExpect(status().isCreated());
        revoke("req-h7", "obs-1", "att-1", 1, "REDACTOR", "过期")
                .andExpect(status().isOk());

        // 公开历史：只返回有效版本，不含替换说明与撤销信息
        getHistory("obs-1", "att-1", null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].status").value("EFFECTIVE"))
                .andExpect(jsonPath("$[0].replacementNote").doesNotExist())
                .andExpect(jsonPath("$[0].revokedAtUtc").doesNotExist());

        // 证据历史：返回全部版本，按版本升序，含撤销信息
        getHistory("obs-1", "att-1", null, null, "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].revokeReason").value("过期"))
                .andExpect(jsonPath("$[0].revokedAtUtc").value("2026-09-27T08:00:00Z"))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].status").value("EFFECTIVE"));
    }

    @Test
    void historyIntervalIsLeftClosedRightOpen() throws Exception {
        createObservation("req-i1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-i2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-i3", "rv-1", "规则一").andExpect(status().isCreated());
        registerRule("req-i4", "rv-2", "规则二").andExpect(status().isCreated());
        registerRule("req-i5", "rv-3", "规则三").andExpect(status().isCreated());

        Instant t0 = FIXED_NOW;
        Instant t1 = t0.plusSeconds(60);
        Instant t2 = t0.plusSeconds(120);
        redact("req-i6", "obs-1", "rv-1", "替换一", "REDACTOR",
                List.of(item("att-1", "脱敏一")))
                .andExpect(status().isCreated());
        advanceTo(t1);
        redact("req-i7", "obs-1", "rv-2", "替换二", "REDACTOR",
                List.of(item("att-1", "脱敏二")))
                .andExpect(status().isCreated());
        advanceTo(t2);
        redact("req-i8", "obs-1", "rv-3", "替换三", "REDACTOR",
                List.of(item("att-1", "脱敏三")))
                .andExpect(status().isCreated());

        // [t0, t1)：只含 v1（左闭右开）
        getHistory("obs-1", "att-1", t0.toString(), t1.toString(), "EVIDENCE")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(1));
        // [t1, t2)：只含 v2
        getHistory("obs-1", "att-1", t1.toString(), t2.toString(), "EVIDENCE")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(2));
        // [t0, t2)：含 v1、v2，不含 v3
        getHistory("obs-1", "att-1", t0.toString(), t2.toString(), "EVIDENCE")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[1].version").value(2));
        // 无区间：全部版本
        getHistory("obs-1", "att-1", null, null, "EVIDENCE")
                .andExpect(jsonPath("$.length()").value(3));
        // from >= to → 400，消息携带实际端点值
        getHistory("obs-1", "att-1", t1.toString(), t1.toString(), "EVIDENCE")
                .andExpect(status().isBadRequest());
        // 非法时间格式 → 400
        getHistory("obs-1", "att-1", "not-a-time", null, "EVIDENCE")
                .andExpect(status().isBadRequest());
    }

    // ---------- 诊断 ----------

    @Test
    void diagnosticsReturnsActualCounts() throws Exception {
        createObservation("req-n1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-n2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerAttachment("req-n3", "obs-1", "att-2", "摘要").andExpect(status().isCreated());
        registerRule("req-n4", "rv-1", "规则一").andExpect(status().isCreated());
        registerRule("req-n5", "rv-2", "规则二").andExpect(status().isCreated());
        redact("req-n6", "obs-1", "rv-1", "替换一", "REDACTOR",
                List.of(item("att-1", "脱敏一")))
                .andExpect(status().isCreated());
        redact("req-n7", "obs-1", "rv-2", "替换二", "REDACTOR",
                List.of(item("att-1", "脱敏二")))
                .andExpect(status().isCreated());
        revoke("req-n8", "obs-1", "att-1", 1, "REDACTOR", "过期")
                .andExpect(status().isOk());

        getDiagnostics("obs-1", "att-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVersions").value(2))
                .andExpect(jsonPath("$.effectiveVersions").value(1))
                .andExpect(jsonPath("$.revokedVersions").value(1))
                .andExpect(jsonPath("$.latestVersion").value(2))
                .andExpect(jsonPath("$.latestEffectiveVersion").value(2));

        // 无脱敏版本的附件：计数全为 0，最新版本为 0，有效版本为空
        getDiagnostics("obs-1", "att-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVersions").value(0))
                .andExpect(jsonPath("$.effectiveVersions").value(0))
                .andExpect(jsonPath("$.revokedVersions").value(0))
                .andExpect(jsonPath("$.latestVersion").value(0))
                .andExpect(jsonPath("$.latestEffectiveVersion").doesNotExist());
    }

    // ---------- 快照引用固化 ----------

    @Test
    void mergeFreezesAttachmentReferencesAndLaterRedactionDoesNotRewrite() throws Exception {
        createObservation("req-m1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-m2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-m3", "rv-1", "规则一").andExpect(status().isCreated());
        registerRule("req-m4", "rv-2", "规则二").andExpect(status().isCreated());
        redact("req-m5", "obs-1", "rv-1", "替换一", "REDACTOR",
                List.of(item("att-1", "脱敏一")))
                .andExpect(status().isCreated());

        // 合并生成观测版本 2：固化 att-1 引用脱敏版本 1
        mergeObservation("req-m6", "obs-1", 1, "合并备注").andExpect(status().isOk());
        getFrozenReferences("obs-1", 2, "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].frozenAtUtc").value("2026-09-27T08:00:00Z"));

        // 后续脱敏生成版本 2：不追溯改写已固化的引用
        redact("req-m7", "obs-1", "rv-2", "替换二", "REDACTOR",
                List.of(item("att-1", "脱敏二")))
                .andExpect(status().isCreated());
        getFrozenReferences("obs-1", 2, "EVIDENCE")
                .andExpect(jsonPath("$[0].redactionVersion").value(1));

        // 再次合并生成版本 3：固化当时最新的有效版本 2
        mergeObservation("req-m8", "obs-1", 2, "再次合并").andExpect(status().isOk());
        getFrozenReferences("obs-1", 3, "EVIDENCE")
                .andExpect(jsonPath("$[0].redactionVersion").value(2));

        // 无角色 → 403；版本不存在 → 404
        getFrozenReferences("obs-1", 2, null).andExpect(status().isForbidden());
        getFrozenReferences("obs-1", 99, "EVIDENCE").andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void requestIdReplayReturnsSameResponseAndDifferentParams409() throws Exception {
        createObservation("req-p1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-p2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-p3", "rv-1", "规则一").andExpect(status().isCreated());

        String first = redact("req-p4", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 同键同参重放：返回首次完整响应，不产生新版本
        String second = redact("req-p4", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(second).isEqualTo(first);
        assertThat(redactionCount("att-1")).isEqualTo(1);

        // 同键异参 → 409
        redact("req-p4", "obs-1", "rv-1", "不同替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isConflict());
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        createObservation("req-q1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-q2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        // 规则未登记 → 422，失败不占键
        redact("req-q3", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isUnprocessableEntity());
        registerRule("req-q4", "rv-1", "规则一").andExpect(status().isCreated());
        // 同一 requestId 换上合法参数后正常执行
        redact("req-q3", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isCreated());
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    // ---------- 读取不改变状态 ----------

    @Test
    void readsDoNotChangeState() throws Exception {
        createObservation("req-z1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-z2", "obs-1", "att-1", "摘要").andExpect(status().isCreated());
        registerRule("req-z3", "rv-1", "规则一").andExpect(status().isCreated());
        redact("req-z4", "obs-1", "rv-1", "替换", "REDACTOR",
                List.of(item("att-1", "脱敏")))
                .andExpect(status().isCreated());

        String before = getDiagnostics("obs-1", "att-1")
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 重复执行全部读取端点
        for (int i = 0; i < 3; i++) {
            getPublicView("obs-1", "att-1").andExpect(status().isOk());
            listPublicViews("obs-1").andExpect(status().isOk());
            getVersionView("obs-1", "att-1", 1, "EVIDENCE").andExpect(status().isOk());
            getHistory("obs-1", "att-1", null, null, "EVIDENCE").andExpect(status().isOk());
            getDiagnostics("obs-1", "att-1").andExpect(status().isOk());
            getFrozenReferences("obs-1", 1, "EVIDENCE").andExpect(status().isOk());
        }
        String after = getDiagnostics("obs-1", "att-1")
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(after).isEqualTo(before);
        assertThat(redactionCount("att-1")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-1'",
                Integer.class)).isEqualTo(1);
    }

    // ---------- 规则登记与撤销 ----------

    @Test
    void ruleRegistrationAndRevocation() throws Exception {
        registerRule("req-k1", "rv-1", "规则一")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value("rv-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.registeredAtUtc").value("2026-09-27T08:00:00Z"))
                .andExpect(jsonPath("$.revokedAtUtc").doesNotExist());

        // 重复登记 → 409；同键同参重放 → 首次响应
        registerRule("req-k2", "rv-1", "规则一").andExpect(status().isConflict());
        registerRule("req-k1", "rv-1", "规则一").andExpect(status().isCreated());
        registerRule("req-k1", "rv-1", "不同描述").andExpect(status().isConflict());

        // 撤销 → 200，状态翻转并记录撤销时刻
        revokeRule("req-k3", "rv-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAtUtc").value("2026-09-27T08:00:00Z"));
        // 重复撤销 → 409；未登记 → 404
        revokeRule("req-k4", "rv-1").andExpect(status().isConflict());
        revokeRule("req-k5", "rv-missing").andExpect(status().isNotFound());
    }
}

