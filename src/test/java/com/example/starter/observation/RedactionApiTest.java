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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件脱敏 API 测试：主流程、失败分支（403/404/409/422）、事务原子性、幂等重放、
 * 证据固化隔离、撤销与物理清除语义、UTC 左闭右开历史区间及诊断数量，全部基于真实 H2 内存库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedactionApiTest {

    private static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-27T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-27T10:30:00Z");

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
        jdbcTemplate.update("DELETE FROM observation_evidence");
        jdbcTemplate.update("DELETE FROM attachment_redaction");
        jdbcTemplate.update("DELETE FROM attachment");
        jdbcTemplate.update("DELETE FROM redaction_rule");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(T0);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void setClock(Instant instant) {
        Mockito.when(clock.instant()).thenReturn(instant);
    }

    // ---------- 基础准备辅助 ----------

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

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String location, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteObservation(String requestId, String observationId, int expectedVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerRule(String requestId, String ruleVersion, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("ruleVersion", ruleVersion);
        body.put("description", description);
        return mockMvc.perform(post("/api/redaction-rules")
                .header("X-Actor-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerAttachment(String requestId, String observationId, String attachmentId,
                                             String summary) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("attachmentId", attachmentId);
        body.put("contentSummary", summary);
        body.put("mediaType", "text/plain");
        return mockMvc.perform(post("/api/observations/{id}/attachments", observationId)
                .header("X-Actor-Role", "REDACTOR")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions redact(String requestId, String observationId, String ruleVersion,
                                 List<Map<String, String>> items, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("ruleVersion", ruleVersion);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/{id}/redactions", observationId)
                .header("X-Actor-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions redact(String requestId, String observationId, String ruleVersion,
                                 List<Map<String, String>> items) throws Exception {
        return redact(requestId, observationId, ruleVersion, items, "REDACTOR");
    }

    private Map<String, String> item(String attachmentId, String note) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("attachmentId", attachmentId);
        map.put("replacementNote", note);
        return map;
    }

    private ResultActions revokeRedaction(String requestId, String redactionId, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/redactions/{id}/revoke", redactionId)
                .header("X-Actor-Role", "REDACTOR")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions revokeRule(String requestId, String ruleVersion, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/redaction-rules/{rule}/revoke", ruleVersion)
                .header("X-Actor-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions purge(String requestId, String observationId, String attachmentId, String reason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/observations/{id}/attachments/{att}/purge", observationId, attachmentId)
                .header("X-Actor-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void seedObservationWithAttachments(String observationId, String... attachmentIds) throws Exception {
        createObservation("req-create-" + observationId, observationId).andExpect(status().isCreated());
        for (String attachmentId : attachmentIds) {
            registerAttachment("req-att-" + attachmentId, observationId, attachmentId,
                    "原始摘要-" + attachmentId).andExpect(status().isCreated());
        }
    }

    private int redactionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 主流程 ----------

    @Test
    void batchRedactionCreatesIncrementalVersionsAndPublicViewShowsLatest() throws Exception {
        registerRule("req-rule-1", "rule-v1", "第一版规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-1", "att-a", "att-b");

        // 脱敏前公开查询返回原始摘要
        mockMvc.perform(get("/api/observations/obs-1/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value("att-a"))
                .andExpect(jsonPath("$[0].displayType").value("ORIGINAL"))
                .andExpect(jsonPath("$[0].summary").value("原始摘要-att-a"))
                .andExpect(jsonPath("$[0].redactionId").doesNotExist());

        setClock(T1);
        redact("req-redact-1", "obs-1", "rule-v1",
                        List.of(item("att-a", "受限摘要A-1"), item("att-b", "受限摘要B-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedCount").value(2))
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.versions[0].attachmentId").value("att-a"))
                .andExpect(jsonPath("$.versions[0].version").value(1))
                .andExpect(jsonPath("$.versions[0].ruleVersion").value("rule-v1"))
                .andExpect(jsonPath("$.versions[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.versions[0].createdAtUtc").value("2026-09-27T09:00:00Z"))
                .andExpect(jsonPath("$.versions[1].attachmentId").value("att-b"))
                .andExpect(jsonPath("$.versions[1].version").value(1));

        // 公开查询只返回最新有效脱敏版本，不返回原始摘要
        mockMvc.perform(get("/api/observations/obs-1/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayType").value("REDACTION"))
                .andExpect(jsonPath("$[0].summary").value("受限摘要A-1"))
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].ruleVersion").value("rule-v1"))
                .andExpect(jsonPath("$[1].summary").value("受限摘要B-1"));

        // 第二次脱敏（新版本规则）生成递增版本 2
        registerRule("req-rule-2", "rule-v2", "第二版规则").andExpect(status().isCreated());
        setClock(T2);
        redact("req-redact-2", "obs-1", "rule-v2", List.of(item("att-a", "受限摘要A-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].version").value(2));
        mockMvc.perform(get("/api/observations/obs-1/attachments/att-a"))
                .andExpect(jsonPath("$.summary").value("受限摘要A-2"))
                .andExpect(jsonPath("$.redactionVersion").value(2));
    }

    // ---------- 失败分支与事务原子性 ----------

    @Test
    void batchFailsWholeRequestWhenOneAttachmentBelongsToOtherObservation() throws Exception {
        registerRule("req-rule-b1", "rule-v1", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-x", "att-x1");
        seedObservationWithAttachments("obs-y", "att-y1");

        redact("req-redact-bad", "obs-x", "rule-v1",
                        List.of(item("att-x1", "替换X"), item("att-y1", "替换Y")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("att-y1")));

        // 整次失败：两个附件都查不到任何半成品脱敏版本
        assertThatRedactionCount(0);
    }

    @Test
    void redactionWithUnregisteredOrRevokedRuleReturns422() throws Exception {
        seedObservationWithAttachments("obs-r", "att-r1");

        // 规则未登记 → 422
        redact("req-redact-norule", "obs-r", "rule-unknown", List.of(item("att-r1", "替换")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("RULE_NOT_REGISTERED"));
        assertThatRedactionCount(0);

        registerRule("req-rule-reg", "rule-v1", "规则").andExpect(status().isCreated());
        revokeRule("req-rule-revoke", "rule-v1", "规则作废").andExpect(status().isOk());

        // 使用已撤销规则 → 422
        redact("req-redact-revokedrule", "obs-r", "rule-v1", List.of(item("att-r1", "替换")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("RULE_REVOKED"));
        assertThatRedactionCount(0);

        // 规则撤销后既有行为不受影响：查询仍可见其登记状态为 REVOKED 及撤销时刻
        mockMvc.perform(get("/api/redaction-rules/rule-v1").header("X-Actor-Role", "REDACTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAtUtc").exists());
    }

    @Test
    void redactionOnTombstoneReturns403AndPublicViewOfTombstoneIs404() throws Exception {
        registerRule("req-rule-t1", "rule-v1", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-t", "att-t1");
        deleteObservation("req-del-t", "obs-t", 1).andExpect(status().isOk());

        redact("req-redact-tomb", "obs-t", "rule-v1", List.of(item("att-t1", "替换")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OBSERVATION_DELETED"));
        mockMvc.perform(get("/api/observations/obs-t/attachments"))
                .andExpect(status().isNotFound());
        // 证据角色仍可读取墓碑观测的附件版本
        mockMvc.perform(get("/api/observations/obs-t/attachments/att-t1/versions/0")
                        .header("X-Actor-Role", "EVIDENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("原始摘要-att-t1"));
    }

    @Test
    void unauthorizedAndUnknownRolesRejected() throws Exception {
        registerRule("req-rule-u1", "rule-v1", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-u", "att-u1");

        // 公开角色不能提交脱敏
        redact("req-redact-public", "obs-u", "rule-v1", List.of(item("att-u1", "替换")), "PUBLIC")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ROLE"));
        // 缺省角色（无头）等同 PUBLIC
        mockMvc.perform(post("/api/observations/obs-u/redactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "req-redact-norole",
                                "ruleVersion", "rule-v1",
                                "items", List.of(item("att-u1", "替换"))))))
                .andExpect(status().isForbidden());
        // 无法识别的角色值 → 403
        redact("req-redact-badrole", "obs-u", "rule-v1", List.of(item("att-u1", "替换")), "AUDITOR")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ROLE"));
        // 公开角色不能读明细
        mockMvc.perform(get("/api/observations/obs-u/redactions"))
                .andExpect(status().isForbidden());
        // 公开角色不能按版本读原始摘要
        mockMvc.perform(get("/api/observations/obs-u/attachments/att-u1/versions/0"))
                .andExpect(status().isForbidden());
        // 非 ADMIN 不能登记规则
        mockMvc.perform(post("/api/redaction-rules")
                        .header("X-Actor-Role", "REDACTOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", "req-rule-denied",
                                "ruleVersion", "rule-v9",
                                "description", "x"))))
                .andExpect(status().isForbidden());
        assertThatRedactionCount(0);
    }

    @Test
    void sameAttachmentWithSameRuleVersionTwiceConflictsAndProducesNothingForSecondRequest() throws Exception {
        registerRule("req-rule-s1", "rule-v1", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-s", "att-s1");
        redact("req-redact-s1", "obs-s", "rule-v1", List.of(item("att-s1", "替换1")))
                .andExpect(status().isOk());
        redact("req-redact-s2", "obs-s", "rule-v1", List.of(item("att-s1", "替换2")))
                .andExpect(status().isConflict());
        // 第二次失败不改写既有版本：公开视图仍为第一次的替换说明与版本 1
        mockMvc.perform(get("/api/observations/obs-s/attachments/att-s1"))
                .andExpect(jsonPath("$.redactionVersion").value(1))
                .andExpect(jsonPath("$.summary").value("替换1"));
    }

    @Test
    void duplicateAttachmentInsideOneRequestIs422AndAtomic() throws Exception {
        registerRule("req-rule-d1", "rule-v1", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-d", "att-d1");
        redact("req-redact-dup", "obs-d", "rule-v1",
                        List.of(item("att-d1", "替换1"), item("att-d1", "替换2")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ATTACHMENT"));
        assertThatRedactionCount(0);
    }

    // ---------- 撤销与物理清除 ----------

    @Test
    void revokeOnlyRemovesPublicEligibilityButKeepsHistory() throws Exception {
        registerRule("req-rule-v1", "rule-v1", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-v", "att-v1");
        redact("req-redact-v1", "obs-v", "rule-v1", List.of(item("att-v1", "受限V1")))
                .andExpect(status().isOk());
        String redactionId = extractRedactionId("obs-v", "att-v1", 1);
        org.assertj.core.api.Assertions.assertThat(redactionId).isNotBlank();

        revokeRedaction("req-revoke-v1", redactionId, "脱敏依据变更").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("REDACTION"))
                .andExpect(jsonPath("$.targetId").value(redactionId))
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAtUtc").exists());

        // 公开视图回退原始摘要；明细仍保留撤销版本、理由和撤销时刻
        mockMvc.perform(get("/api/observations/obs-v/attachments/att-v1"))
                .andExpect(jsonPath("$.displayType").value("ORIGINAL"))
                .andExpect(jsonPath("$.summary").value("原始摘要-att-v1"));
        mockMvc.perform(get("/api/observations/obs-v/redactions").header("X-Actor-Role", "REDACTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].replacementNote").value("受限V1"))
                .andExpect(jsonPath("$[0].revokeReason").value("脱敏依据变更"))
                .andExpect(jsonPath("$[0].revokedAtUtc").exists());
        // 证据角色按版本仍可读取已撤销脱敏版本
        mockMvc.perform(get("/api/observations/obs-v/attachments/att-v1/versions/1")
                        .header("X-Actor-Role", "EVIDENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("受限V1"))
                .andExpect(jsonPath("$.redactionVersion").value(1));

        // 重复撤销：幂等返回同一实际撤销时刻，不覆盖理由与历史
        revokeRedaction("req-revoke-v1-again", redactionId, "另一个理由").andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("脱敏依据变更"));
    }

    @Test
    void purgeNullsOriginalSummaryButRedactionsRemainVisible() throws Exception {
        registerRule("req-rule-p1", "rule-v1", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-p", "att-p1");
        redact("req-redact-p1", "obs-p", "rule-v1", List.of(item("att-p1", "受限P1")))
                .andExpect(status().isOk());

        purge("req-purge-p1", "obs-p", "att-p1", "保留期届满").andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(true))
                .andExpect(jsonPath("$.purgedAtUtc").exists());

        // 公开仍见脱敏版本
        mockMvc.perform(get("/api/observations/obs-p/attachments/att-p1"))
                .andExpect(jsonPath("$.displayType").value("REDACTION"))
                .andExpect(jsonPath("$.summary").value("受限P1"))
                .andExpect(jsonPath("$.purged").value(true));
        // 证据版本 0 的原始摘要为 null，且撤销脱敏也无法恢复
        mockMvc.perform(get("/api/observations/obs-p/attachments/att-p1/versions/0")
                        .header("X-Actor-Role", "EVIDENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayType").value("ORIGINAL"))
                .andExpect(jsonPath("$.summary").doesNotExist());
        Integer summaryNotNull = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE attachment_id = 'att-p1' AND content_summary IS NOT NULL",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(summaryNotNull).isZero();
    }

    // ---------- 幂等 ----------

    @Test
    void requestIdReplaySameParamsReturnsFirstResponseDifferentParamsConflictAndFailureDoesNotOccupyKey()
            throws Exception {
        registerRule("req-rule-i1", "rule-v1", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-i", "att-i1");

        redact("req-redact-idem", "obs-i", "rule-v1", List.of(item("att-i1", "替换首版")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].version").value(1));
        // 同参重放：完整返回首次响应（仍为版本 1），不新增版本
        redact("req-redact-idem", "obs-i", "rule-v1", List.of(item("att-i1", "替换首版")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].version").value(1))
                .andExpect(jsonPath("$.createdCount").value(1));
        // 异参重放：409
        redact("req-redact-idem", "obs-i", "rule-v1", List.of(item("att-i1", "替换改参")))
                .andExpect(status().isConflict());
        assertThatRedactionCount(1);

        // 失败请求不占键：先以不存在附件失败，随后同 requestId 合法请求成功
        redact("req-redact-retry", "obs-i", "rule-v1", List.of(item("att-missing", "替换")))
                .andExpect(status().isNotFound());
        registerRule("req-rule-i2", "rule-v2", "规则2").andExpect(status().isCreated());
        redact("req-redact-retry", "obs-i", "rule-v2", List.of(item("att-i1", "替换二版")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].version").value(2));
    }

    // ---------- 历史区间、明细、诊断 ----------

    @Test
    void historyIsHalfOpenUtcRangeAndDiagnosticsReportActualCounts() throws Exception {
        registerRule("req-rule-h1", "rule-v1", "规则").andExpect(status().isCreated());
        registerRule("req-rule-h2", "rule-v2", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-h", "att-h1", "att-h2");

        setClock(T1);
        redact("req-redact-h1", "obs-h", "rule-v1", List.of(item("att-h1", "H1-1")))
                .andExpect(status().isOk());
        setClock(T2);
        redact("req-redact-h2", "obs-h", "rule-v2",
                        List.of(item("att-h1", "H1-2"), item("att-h2", "H2-1")))
                .andExpect(status().isOk());

        // 左闭右开 [T1, T2) 只含 T1 生成的版本
        mockMvc.perform(get("/api/observations/obs-h/redaction-history")
                        .header("X-Actor-Role", "REDACTOR")
                        .param("from", "2026-09-27T09:00:00Z")
                        .param("to", "2026-09-27T10:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].attachmentId").value("att-h1"))
                .andExpect(jsonPath("$[0].version").value(1));

        // 限定附件 + 仅给 from：包含右端时刻
        mockMvc.perform(get("/api/observations/obs-h/redaction-history")
                        .header("X-Actor-Role", "REDACTOR")
                        .param("attachmentId", "att-h1")
                        .param("from", "2026-09-27T09:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // from >= to → 400
        mockMvc.perform(get("/api/observations/obs-h/redaction-history")
                        .header("X-Actor-Role", "REDACTOR")
                        .param("from", "2026-09-27T11:00:00Z")
                        .param("to", "2026-09-27T10:00:00Z"))
                .andExpect(status().isBadRequest());

        // 诊断：2 附件、3 版本全部 ACTIVE
        mockMvc.perform(get("/api/observations/obs-h/redaction-diagnostics")
                        .header("X-Actor-Role", "REDACTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentCount").value(2))
                .andExpect(jsonPath("$.purgedAttachmentCount").value(0))
                .andExpect(jsonPath("$.redactionVersionCount").value(3))
                .andExpect(jsonPath("$.activeVersionCount").value(3))
                .andExpect(jsonPath("$.revokedVersionCount").value(0))
                .andExpect(jsonPath("$.items[0].attachmentId").value("att-h1"))
                .andExpect(jsonPath("$.items[0].totalVersions").value(2))
                .andExpect(jsonPath("$.items[0].latestActiveVersion").value(2))
                .andExpect(jsonPath("$.items[1].totalVersions").value(1));
    }

    // ---------- 证据固化隔离 ----------

    @Test
    void mergeFreezesAttachmentReferencesAndLaterRedactionsDoNotRewriteThem() throws Exception {
        registerRule("req-rule-e1", "rule-v1", "规则").andExpect(status().isCreated());
        seedObservationWithAttachments("obs-e", "att-e1", "att-e2");

        // 第一次合并（尚无脱敏）：新版本证据固化为 ORIGINAL
        merge("req-merge-e1", "obs-e", 1, "站点B", "第一次合并").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/obs-e/versions/2/evidence")
                        .header("X-Actor-Role", "EVIDENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].attachmentId").value("att-e1"))
                .andExpect(jsonPath("$[0].refType").value("ORIGINAL"))
                .andExpect(jsonPath("$[0].redactionId").doesNotExist())
                .andExpect(jsonPath("$[0].observationVersion").value(2));

        // 脱敏后再次合并：新版本证据固化当时最新有效脱敏版本
        redact("req-redact-e1", "obs-e", "rule-v1", List.of(item("att-e1", "受限E1-1")))
                .andExpect(status().isOk());
        merge("req-merge-e2", "obs-e", 2, "站点C", "第二次合并").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/obs-e/versions/3/evidence")
                        .header("X-Actor-Role", "EVIDENCE"))
                .andExpect(jsonPath("$[0].refType").value("REDACTION"))
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].ruleVersion").value("rule-v1"))
                .andExpect(jsonPath("$[1].refType").value("ORIGINAL"));

        // 撤销旧版本并以新规则生成版本 2：历史证据（版本 2、3）不被追溯替换
        String firstRedactionId = extractRedactionId("obs-e", "att-e1", 1);
        revokeRedaction("req-revoke-e1", firstRedactionId, "作废").andExpect(status().isOk());
        registerRule("req-rule-e2", "rule-v2", "新规则").andExpect(status().isCreated());
        redact("req-redact-e2", "obs-e", "rule-v2", List.of(item("att-e1", "受限E1-2")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/obs-e/versions/2/evidence")
                        .header("X-Actor-Role", "EVIDENCE"))
                .andExpect(jsonPath("$[0].refType").value("ORIGINAL"));
        mockMvc.perform(get("/api/observations/obs-e/versions/3/evidence")
                        .header("X-Actor-Role", "EVIDENCE"))
                .andExpect(jsonPath("$[0].refType").value("REDACTION"))
                .andExpect(jsonPath("$[0].redactionVersion").value(1));
    }

    @Test
    void evidenceReadRejectsNonEvidenceRoleAndUnknownVersion() throws Exception {
        seedObservationWithAttachments("obs-g", "att-g1");
        mockMvc.perform(get("/api/observations/obs-g/versions/1/evidence"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/observations/obs-g/versions/9/evidence")
                        .header("X-Actor-Role", "EVIDENCE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OBSERVATION_VERSION_NOT_FOUND"));
    }

    // ---------- 其他 ----------

    @Test
    void registerAttachmentRejectsDuplicateAndOtherObservationMismatch() throws Exception {
        seedObservationWithAttachments("obs-q", "att-q1");
        registerAttachment("req-att-dup", "obs-q", "att-q1", "重复附件")
                .andExpect(status().isConflict());
        // 附件归属其他观测时按 404
        mockMvc.perform(get("/api/observations/obs-q/attachments/att-other"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_NOT_FOUND"));
        // 观测不存在 → 404
        registerAttachment("req-att-noobs", "obs-missing", "att-x", "摘要")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OBSERVATION_NOT_FOUND"));
    }

    private void assertThatRedactionCount(int expected) {
        org.assertj.core.api.Assertions.assertThat(redactionCount()).isEqualTo(expected);
    }

    /**
     * 经明细查询接口读取指定附件某版本的 redactionId（复用真实查询入口）。
     */
    private String extractRedactionId(String observationId, String attachmentId, int version) throws Exception {
        String json = mockMvc.perform(get("/api/observations/{id}/redactions", observationId)
                        .header("X-Actor-Role", "REDACTOR"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> entries = objectMapper.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
        return entries.stream()
                .filter(entry -> attachmentId.equals(entry.get("attachmentId"))
                        && Integer.valueOf(version).equals(entry.get("version")))
                .map(entry -> (String) entry.get("redactionId"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "redaction not found: " + attachmentId + "@v" + version));
    }
}
