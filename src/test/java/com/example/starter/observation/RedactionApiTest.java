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
 * 观测附件脱敏 API 的主流程、失败分支、权限、幂等、证据固化与诊断测试（真实 H2 内存库，固定 UTC 时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedactionApiTest {

    private static final Instant T0 = Instant.parse("2026-09-28T08:00:00Z");

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
        jdbcTemplate.update("DELETE FROM observation_evidence_ref");
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

    // ---------- 辅助构造 ----------

    private ResultActions createObservation(String requestId, String observationId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "1号井口");
        body.put("reading", "12.345");
        body.put("note", "巡检记录");
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "1号井口");
        body.put("reading", "12.345");
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
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

    private ResultActions restore(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/restore", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerRule(String requestId, String ruleVersion, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("ruleVersion", ruleVersion);
        body.put("description", "规则 " + ruleVersion);
        var request = post("/api/redaction/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (role != null) {
            request.header("X-Role", role);
        }
        return mockMvc.perform(request);
    }

    private ResultActions revokeRule(String requestId, String ruleVersion, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reason", "规则缺陷");
        var request = post("/api/redaction/rules/{rule}/revoke", ruleVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (role != null) {
            request.header("X-Role", role);
        }
        return mockMvc.perform(request);
    }

    private ResultActions registerAttachment(String requestId, String observationId, String attachmentId,
                                              String summary, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("attachmentId", attachmentId);
        body.put("contentSummary", summary);
        var request = post("/api/observations/{id}/attachments", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (role != null) {
            request.header("X-Role", role);
        }
        return mockMvc.perform(request);
    }

    private ResultActions redact(String requestId, String redactionId, String observationId,
                                 List<Map<String, String>> items, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("redactionId", redactionId);
        body.put("items", items);
        var request = post("/api/observations/{id}/redactions", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (role != null) {
            request.header("X-Role", role);
        }
        return mockMvc.perform(request);
    }

    private Map<String, String> item(String attachmentId, String ruleVersion, String note) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("attachmentId", attachmentId);
        map.put("ruleVersion", ruleVersion);
        map.put("replacementNote", note);
        return map;
    }

    private ResultActions revokeRedaction(String requestId, String observationId, String attachmentId,
                                          int version, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reason", "脱敏依据有误");
        var request = post(
                "/api/observations/{id}/attachments/{att}/redactions/{ver}/revoke",
                observationId, attachmentId, version)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (role != null) {
            request.header("X-Role", role);
        }
        return mockMvc.perform(request);
    }

    private ResultActions purge(String requestId, String observationId, String attachmentId, String role)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reason", "合规清除");
        var request = post("/api/observations/{id}/attachments/{att}/purge", observationId, attachmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (role != null) {
            request.header("X-Role", role);
        }
        return mockMvc.perform(request);
    }

    private int redactionCount(String attachmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = ?", Integer.class, attachmentId);
        return count == null ? 0 : count;
    }

    private int evidenceCount(String observationId, int version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_evidence_ref WHERE observation_id = ? AND observation_version = ?",
                Integer.class, observationId, version);
        return count == null ? 0 : count;
    }

    // ---------- 规则版本管理 ----------

    @Test
    void registerRuleSucceedsForOfficerAndRejectsOtherRoles() throws Exception {
        registerRule("req-rule-1", "rule-v1", "REDACTION_OFFICER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value("rule-v1"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.registeredAtUtc").value("2026-09-28T08:00:00Z"));
        // 重复登记 409
        registerRule("req-rule-2", "rule-v1", "REDACTION_OFFICER").andExpect(status().isConflict());
        // 未携带角色（默认 PUBLIC）403
        registerRule("req-rule-3", "rule-v2", null).andExpect(status().isForbidden());
        // 证据读取角色同样无写权限 403
        registerRule("req-rule-4", "rule-v2", "EVIDENCE_READER").andExpect(status().isForbidden());
        // 无法识别的角色 403
        registerRule("req-rule-5", "rule-v2", "ROOT").andExpect(status().isForbidden());
    }

    @Test
    void revokedRuleCannotBeUsedForNewRedaction() throws Exception {
        registerRule("req-rr-1", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        revokeRule("req-rr-2", "rule-v1", "REDACTION_OFFICER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.revokedReason").value("规则缺陷"))
                .andExpect(jsonPath("$.revokedAtUtc").value("2026-09-28T08:00:00Z"));
        // 重复撤销 422
        revokeRule("req-rr-3", "rule-v1", "REDACTION_OFFICER").andExpect(status().isUnprocessableEntity());
        // 撤销不存在的规则 404
        revokeRule("req-rr-4", "rule-missing", "REDACTION_OFFICER").andExpect(status().isNotFound());

        createObservation("req-rr-5", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-rr-6", "obs-1", "att-1", "原始摘要", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        redact("req-rr-7", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "替换")), "REDACTION_OFFICER")
                .andExpect(status().isUnprocessableEntity());
        assertThat(redactionCount("att-1")).isZero();
    }

    // ---------- 附件登记 ----------

    @Test
    void registerAttachmentValidatesObservationAndUniqueness() throws Exception {
        // 观测不存在 404
        registerAttachment("req-ra-1", "obs-x", "att-1", "摘要", "REDACTION_OFFICER")
                .andExpect(status().isNotFound());
        createObservation("req-ra-2", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-ra-3", "obs-1", "att-1", "原始摘要", "REDACTION_OFFICER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentId").value("att-1"))
                .andExpect(jsonPath("$.contentSummary").value("原始摘要"));
        // 附件标识重复 409
        registerAttachment("req-ra-4", "obs-1", "att-1", "另一个摘要", "REDACTION_OFFICER")
                .andExpect(status().isConflict());
        // 路径与请求体观测标识不一致 422：路径 obs-1、请求体 obs-other（该观测不存在也先判不一致）
        Map<String, Object> mismatchedBody = new LinkedHashMap<>();
        mismatchedBody.put("requestId", "req-ra-5");
        mismatchedBody.put("observationId", "obs-other");
        mismatchedBody.put("attachmentId", "att-2");
        mismatchedBody.put("contentSummary", "摘要");
        mockMvc.perform(post("/api/observations/{id}/attachments", "obs-1")
                        .header("X-Role", "REDACTION_OFFICER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mismatchedBody)))
                .andExpect(status().isUnprocessableEntity());
        // 无权限 403
        registerAttachment("req-ra-6", "obs-1", "att-3", "摘要", null).andExpect(status().isForbidden());

        // 墓碑观测不允许登记附件：404
        delete("req-ra-7", "obs-1", 1).andExpect(status().isOk());
        registerAttachment("req-ra-8", "obs-1", "att-9", "摘要", "REDACTION_OFFICER")
                .andExpect(status().isNotFound());
    }

    // ---------- 脱敏主流程 ----------

    @Test
    void redactCreatesIncrementalVersionsAndPublicViewFollowsLatestActive() throws Exception {
        registerRule("req-rd-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        registerRule("req-rd-1", "rule-v2", "REDACTION_OFFICER").andExpect(status().isCreated());
        createObservation("req-rd-2", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-rd-3", "obs-1", "att-1", "原始敏感摘要", "REDACTION_OFFICER")
                .andExpect(status().isCreated());

        // 未脱敏前：公开查询返回原始摘要
        mockMvc.perform(get("/api/observations/{id}/attachments", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$[0].summary").value("原始敏感摘要"))
                .andExpect(jsonPath("$[0].redacted").value(false))
                .andExpect(jsonPath("$[0].redactionVersion").doesNotExist());

        redact("req-rd-4", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "证件号替换为 ***")), "REDACTION_OFFICER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.redactionId").value("red-1"))
                .andExpect(jsonPath("$.items[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$.items[0].redactionVersion").value(1))
                .andExpect(jsonPath("$.items[0].displaySummary")
                        .value("[REDACTED rule=rule-v1] 证件号替换为 ***"))
                .andExpect(jsonPath("$.items[0].createdAtUtc").value("2026-09-28T08:00:00Z"));

        // 公开视图切换为最新有效脱敏版本
        mockMvc.perform(get("/api/observations/{id}/attachments", "obs-1"))
                .andExpect(jsonPath("$[0].summary").value("[REDACTED rule=rule-v1] 证件号替换为 ***"))
                .andExpect(jsonPath("$[0].redacted").value(true))
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].ruleVersion").value("rule-v1"));

        // 同一附件再用另一规则脱敏：版本递增为 2
        redact("req-rd-5", "red-2", "obs-1",
                List.of(item("att-1", "rule-v2", "全部个人信息遮蔽")), "REDACTION_OFFICER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].redactionVersion").value(2));
        mockMvc.perform(get("/api/observations/{id}/attachments", "obs-1"))
                .andExpect(jsonPath("$[0].redactionVersion").value(2))
                .andExpect(jsonPath("$[0].ruleVersion").value("rule-v2"));

        // 同一附件重复使用同一规则版本：422
        redact("req-rd-6", "red-3", "obs-1",
                List.of(item("att-1", "rule-v1", "再次替换")), "REDACTION_OFFICER")
                .andExpect(status().isUnprocessableEntity());
        assertThat(redactionCount("att-1")).isEqualTo(2);

        // 撤销版本 2：公开视图回退到版本 1
        revokeRedaction("req-rd-7", "obs-1", "att-1", 2, "REDACTION_OFFICER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.revokedAtUtc").value("2026-09-28T08:00:00Z"));
        mockMvc.perform(get("/api/observations/{id}/attachments", "obs-1"))
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].ruleVersion").value("rule-v1"));

        // 再撤销版本 1：无有效脱敏，公开视图回退为原始摘要
        revokeRedaction("req-rd-8", "obs-1", "att-1", 1, "REDACTION_OFFICER").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/{id}/attachments", "obs-1"))
                .andExpect(jsonPath("$[0].summary").value("原始敏感摘要"))
                .andExpect(jsonPath("$[0].redacted").value(false));

        // 重复撤销 422；撤销不存在版本 404
        revokeRedaction("req-rd-9", "obs-1", "att-1", 1, "REDACTION_OFFICER")
                .andExpect(status().isUnprocessableEntity());
        revokeRedaction("req-rd-10", "obs-1", "att-1", 9, "REDACTION_OFFICER")
                .andExpect(status().isNotFound());
        // 脱敏行（含撤销理由与时刻）全部保留
        assertThat(redactionCount("att-1")).isEqualTo(2);
    }

    @Test
    void redactMultipleAttachmentsSucceedsAtomically() throws Exception {
        registerRule("req-rm-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        registerRule("req-rm-0b", "rule-v2", "REDACTION_OFFICER").andExpect(status().isCreated());
        createObservation("req-rm-1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-rm-2", "obs-1", "att-1", "摘要1", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        registerAttachment("req-rm-3", "obs-1", "att-2", "摘要2", "REDACTION_OFFICER")
                .andExpect(status().isCreated());

        redact("req-rm-4", "red-multi", "obs-1",
                List.of(item("att-1", "rule-v1", "替换1"), item("att-2", "rule-v1", "替换2")),
                "REDACTION_OFFICER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$.items[0].redactionVersion").value(1))
                .andExpect(jsonPath("$.items[1].attachmentId").value("att-2"))
                .andExpect(jsonPath("$.items[1].redactionVersion").value(1));
        assertThat(redactionCount("att-1")).isEqualTo(1);
        assertThat(redactionCount("att-2")).isEqualTo(1);

        // 请求内同一附件重复：400，不产生版本（使用尚未应用的 rule-v2 以命中重复附件校验）
        redact("req-rm-5", "red-dup", "obs-1",
                List.of(item("att-1", "rule-v2", "x"), item("att-1", "rule-v2", "y")),
                "REDACTION_OFFICER")
                .andExpect(status().isBadRequest());
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    @Test
    void redactFailsWholeRequestWhenAnyItemInvalid() throws Exception {
        registerRule("req-rf-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        registerRule("req-rf-1", "rule-bad", "REDACTION_OFFICER").andExpect(status().isCreated());
        revokeRule("req-rf-2", "rule-bad", "REDACTION_OFFICER").andExpect(status().isOk());
        createObservation("req-rf-3", "obs-1").andExpect(status().isCreated());
        createObservation("req-rf-4", "obs-2").andExpect(status().isCreated());
        registerAttachment("req-rf-5", "obs-1", "att-1", "摘要1", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        registerAttachment("req-rf-6", "obs-1", "att-2", "摘要2", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        registerAttachment("req-rf-7", "obs-2", "att-other", "其他观测附件", "REDACTION_OFFICER")
                .andExpect(status().isCreated());

        // 第二项归属其他观测：422，两个附件都不产生版本
        redact("req-rf-8", "red-bad-owner", "obs-1",
                List.of(item("att-1", "rule-v1", "替换1"), item("att-other", "rule-v1", "替换2")),
                "REDACTION_OFFICER")
                .andExpect(status().isUnprocessableEntity());
        assertThat(redactionCount("att-1")).isZero();
        assertThat(redactionCount("att-other")).isZero();

        // 第二项使用已撤销规则：422，整次不落库
        redact("req-rf-9", "red-bad-rule", "obs-1",
                List.of(item("att-1", "rule-v1", "替换1"), item("att-2", "rule-bad", "替换2")),
                "REDACTION_OFFICER")
                .andExpect(status().isUnprocessableEntity());
        assertThat(redactionCount("att-1")).isZero();
        assertThat(redactionCount("att-2")).isZero();

        // 第二项使用未登记规则：404，整次不落库
        redact("req-rf-10", "red-missing-rule", "obs-1",
                List.of(item("att-1", "rule-v1", "替换1"), item("att-2", "rule-nope", "替换2")),
                "REDACTION_OFFICER")
                .andExpect(status().isNotFound());
        assertThat(redactionCount("att-1")).isZero();
        assertThat(redactionCount("att-2")).isZero();

        // 第二项附件不存在：404，整次不落库
        redact("req-rf-11", "red-missing-att", "obs-1",
                List.of(item("att-1", "rule-v1", "替换1"), item("att-nope", "rule-v1", "替换2")),
                "REDACTION_OFFICER")
                .andExpect(status().isNotFound());
        assertThat(redactionCount("att-1")).isZero();

        // 墓碑观测：404，整次不落库
        delete("req-rf-12", "obs-2", 1).andExpect(status().isOk());
        redact("req-rf-13", "red-tomb", "obs-2",
                List.of(item("att-other", "rule-v1", "替换")), "REDACTION_OFFICER")
                .andExpect(status().isNotFound());
        assertThat(redactionCount("att-other")).isZero();

        // 未授权角色：403，不产生版本
        redact("req-rf-14", "red-forbidden", "obs-1",
                List.of(item("att-1", "rule-v1", "替换1")), null)
                .andExpect(status().isForbidden());
        assertThat(redactionCount("att-1")).isZero();
    }

    // ---------- 证据视图 ----------

    @Test
    void evidenceReaderSeesOriginalAndAllVersionsIncludingRevoked() throws Exception {
        registerRule("req-ev-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        createObservation("req-ev-1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-ev-2", "obs-1", "att-1", "原始敏感摘要", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        redact("req-ev-3", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "替换说明")), "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        revokeRedaction("req-ev-4", "obs-1", "att-1", 1, "REDACTION_OFFICER").andExpect(status().isOk());

        // 无证据权限：403
        mockMvc.perform(get("/api/observations/{id}/attachments/{att}/evidence", "obs-1", "att-1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/observations/{id}/attachments/{att}/evidence", "obs-1", "att-1")
                        .header("X-Role", "REDACTION_OFFICER"))
                .andExpect(status().isForbidden());

        // 证据读取者：原始摘要、清除标记、含已撤销版本的完整历史
        mockMvc.perform(get("/api/observations/{id}/attachments/{att}/evidence", "obs-1", "att-1")
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalSummary").value("原始敏感摘要"))
                .andExpect(jsonPath("$.contentPurged").value(false))
                .andExpect(jsonPath("$.redactions[0].redactionVersion").value(1))
                .andExpect(jsonPath("$.redactions[0].ruleVersion").value("rule-v1"))
                .andExpect(jsonPath("$.redactions[0].displaySummary")
                        .value("[REDACTED rule=rule-v1] 替换说明"))
                .andExpect(jsonPath("$.redactions[0].replacementNote").value("替换说明"))
                .andExpect(jsonPath("$.redactions[0].active").value(false))
                .andExpect(jsonPath("$.redactions[0].revokeReason").value("脱敏依据有误"))
                .andExpect(jsonPath("$.redactions[0].revokedAtUtc").value("2026-09-28T08:00:00Z"));
    }

    @Test
    void purgedAttachmentKeepsRedactionsButOriginalIsUnrecoverable() throws Exception {
        registerRule("req-pg-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        createObservation("req-pg-1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-pg-2", "obs-1", "att-1", "原始敏感摘要", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        redact("req-pg-3", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "替换说明")), "REDACTION_OFFICER")
                .andExpect(status().isCreated());

        purge("req-pg-4", "obs-1", "att-1", "REDACTION_OFFICER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentPurged").value(true))
                .andExpect(jsonPath("$.purgedAtUtc").value("2026-09-28T08:00:00Z"));
        // 重复物理清除 422
        purge("req-pg-5", "obs-1", "att-1", "REDACTION_OFFICER")
                .andExpect(status().isUnprocessableEntity());
        // 附件不存在 404；无权限 403
        purge("req-pg-6", "obs-1", "att-nope", "REDACTION_OFFICER").andExpect(status().isNotFound());
        purge("req-pg-7", "obs-1", "att-1", null).andExpect(status().isForbidden());

        // 公开视图仍由有效脱敏版本提供
        mockMvc.perform(get("/api/observations/{id}/attachments", "obs-1"))
                .andExpect(jsonPath("$[0].summary").value("[REDACTED rule=rule-v1] 替换说明"));
        // 证据视图：原文不可恢复（null），脱敏版本与历史仍完整
        mockMvc.perform(get("/api/observations/{id}/attachments/{att}/evidence", "obs-1", "att-1")
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalSummary").doesNotExist())
                .andExpect(jsonPath("$.contentPurged").value(true))
                .andExpect(jsonPath("$.redactions.length()").value(1))
                .andExpect(jsonPath("$.redactions[0].displaySummary")
                        .value("[REDACTED rule=rule-v1] 替换说明"));
        // 撤销脱敏不能恢复已物理清除的内容：撤销后公开视图摘要为 null
        revokeRedaction("req-pg-8", "obs-1", "att-1", 1, "REDACTION_OFFICER").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/{id}/attachments", "obs-1"))
                .andExpect(jsonPath("$[0].redacted").value(false))
                .andExpect(jsonPath("$[0].summary").doesNotExist());
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    // ---------- 证据固化与不回写 ----------

    @Test
    void mergeSnapshotFreezesEvidenceAndLaterRedactionsDoNotRewrite() throws Exception {
        registerRule("req-fz-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        registerRule("req-fz-1", "rule-v2", "REDACTION_OFFICER").andExpect(status().isCreated());
        createObservation("req-fz-2", "obs-1").andExpect(status().isCreated());
        // v1 创建时尚无附件：证据列表为空；后续登记附件不回写历史版本
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 1)
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        registerAttachment("req-fz-3", "obs-1", "att-1", "原始敏感摘要", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        // 附件登记后重查 v1：证据仍为空（旧证据不可回写）
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 1)
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(jsonPath("$.items.length()").value(0));

        // 脱敏 r1 后合并生成 v2：固化 r1
        redact("req-fz-4", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "替换v1")), "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        merge("req-fz-5", "obs-1", 1, "合并备注").andExpect(status().isOk());
        assertThat(evidenceCount("obs-1", 2)).isEqualTo(1);
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 2)
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(jsonPath("$.items[0].redactionVersion").value(1))
                .andExpect(jsonPath("$.items[0].displaySummary").value("[REDACTED rule=rule-v1] 替换v1"));

        // 再次脱敏 r2 并撤销 r1：公开视图变为 r2，但 v2 固化证据仍引用 r1
        redact("req-fz-6", "red-2", "obs-1",
                List.of(item("att-1", "rule-v2", "替换v2")), "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        revokeRedaction("req-fz-7", "obs-1", "att-1", 1, "REDACTION_OFFICER").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/{id}/attachments", "obs-1"))
                .andExpect(jsonPath("$[0].redactionVersion").value(2));
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 2)
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(jsonPath("$.items[0].redactionVersion").value(1))
                .andExpect(jsonPath("$.items[0].displaySummary").value("[REDACTED rule=rule-v1] 替换v1"));

        // v3 合并时最新有效为 r2：固化 r2
        merge("req-fz-8", "obs-1", 2, "再次合并").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 3)
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(jsonPath("$.items[0].redactionVersion").value(2))
                .andExpect(jsonPath("$.items[0].displaySummary").value("[REDACTED rule=rule-v2] 替换v2"));

        // 物理清除后：v1 本就无证据；v2 固化的原始摘要不可恢复，但固化的脱敏展示文本仍在
        purge("req-fz-9", "obs-1", "att-1", "REDACTION_OFFICER").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 1)
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 2)
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(jsonPath("$.items[0].originalSummary").doesNotExist())
                .andExpect(jsonPath("$.items[0].displaySummary").value("[REDACTED rule=rule-v1] 替换v1"));

        // 版本不存在 404；无证据权限 403
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 99)
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 2))
                .andExpect(status().isForbidden());
    }

    // ---------- 墓碑恢复 ----------

    @Test
    void restoreRecreatesLiveVersionAndFreezesCurrentEvidence() throws Exception {
        registerRule("req-rs-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        createObservation("req-rs-1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-rs-2", "obs-1", "att-1", "原始敏感摘要", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        merge("req-rs-3", "obs-1", 1, "合并备注").andExpect(status().isOk());
        redact("req-rs-4", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "替换v1")), "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        delete("req-rs-5", "obs-1", 2).andExpect(status().isOk());

        // 墓碑上恢复：版本不匹配 409（当前墓碑为 v3）；匹配后以 v2 内容生成 v4 恢复
        restore("req-rs-6", "obs-1", 9).andExpect(status().isConflict());
        restore("req-rs-7", "obs-1", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.note").value("合并备注"));
        // 恢复版本按提交时刻固化最新有效脱敏（r1）
        mockMvc.perform(get("/api/observations/{id}/versions/{ver}/evidence", "obs-1", 4)
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(jsonPath("$.items[0].redactionVersion").value(1));
        // 恢复后可继续脱敏
        registerRule("req-rs-8", "rule-v2", "REDACTION_OFFICER").andExpect(status().isCreated());
        redact("req-rs-9", "red-2", "obs-1",
                List.of(item("att-1", "rule-v2", "替换v2")), "REDACTION_OFFICER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].redactionVersion").value(2));
        // 对存活观测调用恢复：422
        restore("req-rs-10", "obs-1", 4).andExpect(status().isUnprocessableEntity());
        // 观测不存在 404
        restore("req-rs-11", "obs-x", 1).andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void redactionIdempotencyReplaysSameParamsAndRejectsDifferentParams() throws Exception {
        registerRule("req-id-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        createObservation("req-id-1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-id-2", "obs-1", "att-1", "原始摘要", "REDACTION_OFFICER")
                .andExpect(status().isCreated());

        // 同 requestId 同参重放：返回首次完整结果（含 201 状态），不产生新版本
        for (int i = 0; i < 2; i++) {
            redact("req-id-3", "red-1", "obs-1",
                    List.of(item("att-1", "rule-v1", "替换")), "REDACTION_OFFICER")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.items[0].redactionVersion").value(1));
        }
        assertThat(redactionCount("att-1")).isEqualTo(1);

        // 同 requestId 异参：409
        redact("req-id-3", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "不同替换说明")), "REDACTION_OFFICER")
                .andExpect(status().isConflict());

        // 同 redactionId、新 requestId、同参：按既有 resolutionId 语义重放首次结果（200），不产生新版本
        redact("req-id-4", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "替换")), "REDACTION_OFFICER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].redactionVersion").value(1));
        assertThat(redactionCount("att-1")).isEqualTo(1);

        // 同 redactionId 改参：409
        redact("req-id-5", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "又不同")), "REDACTION_OFFICER")
                .andExpect(status().isConflict());
    }

    @Test
    void failedRedactionDoesNotOccupyRequestId() throws Exception {
        registerRule("req-fp-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        createObservation("req-fp-1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-fp-2", "obs-1", "att-1", "原始摘要", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        // 首次失败（附件不存在）：不占键
        redact("req-fp-3", "red-bad", "obs-1",
                List.of(item("att-nope", "rule-v1", "替换")), "REDACTION_OFFICER")
                .andExpect(status().isNotFound());
        // 同一 requestId 换上合法参数成功
        redact("req-fp-3", "red-ok", "obs-1",
                List.of(item("att-1", "rule-v1", "替换")), "REDACTION_OFFICER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].redactionVersion").value(1));
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    // ---------- 诊断 ----------

    @Test
    void diagnosticsCountsHalfOpenUtcWindowWithActualExpectedAndDifference() throws Exception {
        registerRule("req-dg-0", "rule-v1", "REDACTION_OFFICER").andExpect(status().isCreated());
        createObservation("req-dg-1", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-dg-2", "obs-1", "att-1", "摘要1", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        registerAttachment("req-dg-3", "obs-1", "att-2", "摘要2", "REDACTION_OFFICER")
                .andExpect(status().isCreated());
        redact("req-dg-4", "red-1", "obs-1",
                List.of(item("att-1", "rule-v1", "替换1"), item("att-2", "rule-v1", "替换2")),
                "REDACTION_OFFICER").andExpect(status().isCreated());

        // 左闭右开：createdAt=T0 落在 [T0, T0+1min)，实际 2，要求 3，数量差额 -1；窗口实际 60s，要求 90s，时长差额 -30
        mockMvc.perform(get("/api/redaction/diagnostics")
                        .header("X-Role", "EVIDENCE_READER")
                        .param("fromUtc", "2026-09-28T08:00:00Z")
                        .param("toUtc", "2026-09-28T08:01:00Z")
                        .param("expectedCount", "3")
                        .param("expectedDurationSeconds", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualCount").value(2))
                .andExpect(jsonPath("$.expectedCount").value(3))
                .andExpect(jsonPath("$.countDifference").value(-1))
                .andExpect(jsonPath("$.actualDurationSeconds").value(60))
                .andExpect(jsonPath("$.expectedDurationSeconds").value(90))
                .andExpect(jsonPath("$.durationDifference").value(-30));
        // 终点等于 T0 的区间 [T0-1s, T0)：不含 T0，计数 0，数量差额 0-2=-2；未给时长要求值则时长差额缺省
        mockMvc.perform(get("/api/redaction/diagnostics")
                        .header("X-Role", "EVIDENCE_READER")
                        .param("fromUtc", "2026-09-28T07:59:59Z")
                        .param("toUtc", "2026-09-28T08:00:00Z")
                        .param("expectedCount", "2"))
                .andExpect(jsonPath("$.actualCount").value(0))
                .andExpect(jsonPath("$.countDifference").value(-2))
                .andExpect(jsonPath("$.actualDurationSeconds").value(1))
                .andExpect(jsonPath("$.durationDifference").doesNotExist());
        // 起点晚于 T0：计数 0
        mockMvc.perform(get("/api/redaction/diagnostics")
                        .header("X-Role", "EVIDENCE_READER")
                        .param("fromUtc", "2026-09-28T08:00:01Z")
                        .param("toUtc", "2026-09-28T08:02:00Z"))
                .andExpect(jsonPath("$.actualCount").value(0))
                .andExpect(jsonPath("$.expectedCount").value(0))
                .andExpect(jsonPath("$.actualDurationSeconds").value(119));
        // 非法区间（from >= to）400；时间格式错误 400；无权限 403
        mockMvc.perform(get("/api/redaction/diagnostics")
                        .header("X-Role", "EVIDENCE_READER")
                        .param("fromUtc", "2026-09-28T08:00:00Z")
                        .param("toUtc", "2026-09-28T08:00:00Z"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/redaction/diagnostics")
                        .header("X-Role", "EVIDENCE_READER")
                        .param("fromUtc", "not-a-time")
                        .param("toUtc", "2026-09-28T08:00:00Z"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/redaction/diagnostics")
                        .param("fromUtc", "2026-09-28T08:00:00Z")
                        .param("toUtc", "2026-09-28T08:01:00Z"))
                .andExpect(status().isForbidden());
    }
}
