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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测附件脱敏 API 的主流程、失败分支、幂等与查询边界测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedactionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_version_attachment");
        jdbcTemplate.update("DELETE FROM redaction_version");
        jdbcTemplate.update("DELETE FROM redaction_rule");
        jdbcTemplate.update("DELETE FROM observation_attachment");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    // ---------- 请求构造 ----------

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

    private ResultActions deleteObservation(String requestId, String observationId, int expectedVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerAttachment(String requestId, String observationId, String role,
                                             String attachmentId, String contentSummary) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("role", role);
        body.put("attachmentId", attachmentId);
        body.put("filename", attachmentId + ".txt");
        body.put("mediaType", "text/plain");
        body.put("contentSummary", contentSummary);
        return mockMvc.perform(post("/api/observations/{id}/attachments", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerRule(String requestId, String role, String ruleVersion,
                                       List<String> maskTerms, String replacementToken) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("role", role);
        body.put("ruleVersion", ruleVersion);
        body.put("description", "规则 " + ruleVersion);
        body.put("maskTerms", maskTerms);
        body.put("replacementToken", replacementToken);
        return mockMvc.perform(post("/api/redaction-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions revokeRule(String requestId, String role, String ruleVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("role", role);
        body.put("operator", "op-1");
        body.put("reason", "规则下线");
        return mockMvc.perform(post("/api/redaction-rules/{rv}/revoke", ruleVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions redact(String requestId, String observationId, String role, String ruleVersion,
                                 List<Map<String, String>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("role", role);
        body.put("operator", "op-1");
        body.put("ruleVersion", ruleVersion);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/{id}/redactions", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, String> item(String attachmentId, String replacementNote) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("attachmentId", attachmentId);
        item.put("replacementNote", replacementNote);
        return item;
    }

    private ResultActions revokeRedaction(String requestId, String role, String attachmentId, int version)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("role", role);
        body.put("operator", "op-1");
        body.put("reason", "撤销脱敏");
        return mockMvc.perform(post("/api/attachments/{aid}/redactions/{v}/revoke", attachmentId, version)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions purge(String requestId, String role, String attachmentId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("role", role);
        body.put("operator", "op-1");
        body.put("reason", "合规清除");
        return mockMvc.perform(post("/api/attachments/{aid}/purge", attachmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions publicAttachments(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/attachments/public", observationId));
    }

    private ResultActions evidence(String attachmentId, String role) throws Exception {
        return mockMvc.perform(get("/api/attachments/{aid}/evidence", attachmentId)
                .param("role", role));
    }

    private ResultActions history(String attachmentId, String role) throws Exception {
        return mockMvc.perform(get("/api/attachments/{aid}/redactions", attachmentId)
                .param("role", role));
    }

    private ResultActions redactionVersion(String attachmentId, int version, String role) throws Exception {
        return mockMvc.perform(get("/api/attachments/{aid}/redactions/{v}", attachmentId, version)
                .param("role", role));
    }

    private int redactionCount(String attachmentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM redaction_version WHERE attachment_id = ?", Integer.class, attachmentId);
        return count == null ? 0 : count;
    }

    private void setupObservationWithAttachment(String obs, String attachment, String summary) throws Exception {
        createObservation("req-setup-c-" + obs, obs).andExpect(status().isCreated());
        registerAttachment("req-setup-a-" + attachment, obs, "REDACTOR", attachment, summary)
                .andExpect(status().isCreated());
    }

    // ---------- 附件登记 ----------

    @Test
    void registerAttachmentReturnsCreatedAndEvidenceReadsOriginal() throws Exception {
        createObservation("req-a0", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-a1", "obs-1", "REDACTOR", "att-1", "井口压力 12.5 MPa")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentId").value("att-1"))
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.contentSummary").value("井口压力 12.5 MPa"))
                .andExpect(jsonPath("$.contentPurged").value(false));

        evidence("att-1", "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentSummary").value("井口压力 12.5 MPa"))
                .andExpect(jsonPath("$.contentDigest").isNotEmpty());
    }

    @Test
    void registerAttachmentValidationFailures() throws Exception {
        createObservation("req-b0", "obs-1").andExpect(status().isCreated());
        // 角色不足
        registerAttachment("req-b1", "obs-1", "PUBLIC", "att-1", "内容")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_ROLE"));
        registerAttachment("req-b2", "obs-1", "EVIDENCE", "att-1", "内容")
                .andExpect(status().isForbidden());
        // 观测不存在
        registerAttachment("req-b3", "obs-x", "REDACTOR", "att-1", "内容")
                .andExpect(status().isNotFound());
        // 墓碑观测
        createObservation("req-b4", "obs-2").andExpect(status().isCreated());
        deleteObservation("req-b5", "obs-2", 1).andExpect(status().isOk());
        registerAttachment("req-b6", "obs-2", "REDACTOR", "att-2", "内容")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OBSERVATION_TOMBSTONE"));
        // 重复附件
        registerAttachment("req-b7", "obs-1", "REDACTOR", "att-1", "内容").andExpect(status().isCreated());
        registerAttachment("req-b8", "obs-1", "REDACTOR", "att-1", "其他")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_EXISTS"));
    }

    // ---------- 规则登记与撤销 ----------

    @Test
    void registerAndRevokeRule() throws Exception {
        registerRule("req-r1", "REDACTOR", "rule-v1", List.of("MPa", "井口"), "***")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ruleVersion").value("rule-v1"));
        // 重复登记
        registerRule("req-r2", "REDACTOR", "rule-v1", List.of("x"), "***")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RULE_EXISTS"));
        // 角色不足
        registerRule("req-r3", "PUBLIC", "rule-v2", List.of("x"), "***")
                .andExpect(status().isForbidden());
        // 撤销
        revokeRule("req-r4", "ADMIN", "rule-v1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAtUtc").isNotEmpty());
        // 重复撤销
        revokeRule("req-r5", "ADMIN", "rule-v1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("RULE_REVOKED"));
        // 撤销不存在的规则
        revokeRule("req-r6", "ADMIN", "rule-x")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RULE_NOT_FOUND"));
    }

    // ---------- 脱敏主流程 ----------

    @Test
    void redactSingleAttachmentCreatesVersionAndPublicSeesRedacted() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "井口压力 12.5 MPa 异常");
        registerRule("req-d1", "REDACTOR", "rule-v1", List.of("MPa", "井口"), "***")
                .andExpect(status().isCreated());

        redact("req-d2", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "隐藏压力与位置")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedCount").value(1))
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.versions[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$.versions[0].version").value(1))
                .andExpect(jsonPath("$.versions[0].redactedSummary").value("***压力 12.5 *** 异常"))
                .andExpect(jsonPath("$.versions[0].status").value("ACTIVE"));

        // 公开查询只返回最新有效脱敏版本，不暴露原始摘要
        publicAttachments("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].redactedSummary").value("***压力 12.5 *** 异常"))
                .andExpect(jsonPath("$[0].contentSummary").doesNotExist());

        // 证据权限按版本读取脱敏摘要
        redactionVersion("att-1", 1, "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redactedSummary").value("***压力 12.5 *** 异常"))
                .andExpect(jsonPath("$.replacementNote").value("隐藏压力与位置"));
        // 证据权限读取原始摘要（未被脱敏修改）
        evidence("att-1", "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentSummary").value("井口压力 12.5 MPa 异常"));
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    @Test
    void redactMultipleAttachmentsIsAtomic() throws Exception {
        createObservation("req-e0", "obs-1").andExpect(status().isCreated());
        registerAttachment("req-e1", "obs-1", "REDACTOR", "att-1", "秘密A").andExpect(status().isCreated());
        registerAttachment("req-e2", "obs-1", "REDACTOR", "att-2", "秘密B").andExpect(status().isCreated());
        registerRule("req-e3", "REDACTOR", "rule-v1", List.of("秘密"), "***").andExpect(status().isCreated());

        redact("req-e4", "obs-1", "REDACTOR", "rule-v1",
                List.of(item("att-1", "隐藏A"), item("att-2", "隐藏B")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedCount").value(2))
                .andExpect(jsonPath("$.createdCount").value(2));
        assertThat(redactionCount("att-1")).isEqualTo(1);
        assertThat(redactionCount("att-2")).isEqualTo(1);
    }

    @Test
    void redactMultiAttachmentRollsBackEntirelyOnAnyFailure() throws Exception {
        createObservation("req-f0", "obs-1").andExpect(status().isCreated());
        createObservation("req-f1", "obs-2").andExpect(status().isCreated());
        registerAttachment("req-f2", "obs-1", "REDACTOR", "att-1", "秘密A").andExpect(status().isCreated());
        // att-2 属于 obs-2，不属于 obs-1
        registerAttachment("req-f3", "obs-2", "REDACTOR", "att-2", "秘密B").andExpect(status().isCreated());
        registerRule("req-f4", "REDACTOR", "rule-v1", List.of("秘密"), "***").andExpect(status().isCreated());

        // att-1 合法，att-2 归属不符 → 整次 422，两个附件都不产生脱敏版本
        redact("req-f5", "obs-1", "REDACTOR", "rule-v1",
                List.of(item("att-1", "隐藏A"), item("att-2", "隐藏B")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_OWNERSHIP"));
        assertThat(redactionCount("att-1")).isZero();
        assertThat(redactionCount("att-2")).isZero();
        // 失败不占键：修正为全部合法后同一 requestId 可成功
        redact("req-f5", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "隐藏A")))
                .andExpect(status().isCreated());
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    @Test
    void redactFailureBranches() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密数据");
        registerRule("req-g1", "REDACTOR", "rule-v1", List.of("秘密"), "***").andExpect(status().isCreated());
        registerRule("req-g2", "REDACTOR", "rule-v2", List.of("数据"), "###").andExpect(status().isCreated());
        revokeRule("req-g3", "ADMIN", "rule-v2").andExpect(status().isOk());

        // 角色不足
        redact("req-g4", "obs-1", "PUBLIC", "rule-v1", List.of(item("att-1", "n")))
                .andExpect(status().isForbidden());
        redact("req-g5", "obs-1", "EVIDENCE", "rule-v1", List.of(item("att-1", "n")))
                .andExpect(status().isForbidden());
        // 附件不存在
        redact("req-g6", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-x", "n")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_NOT_FOUND"));
        // 规则未登记
        redact("req-g7", "obs-1", "REDACTOR", "rule-x", List.of(item("att-1", "n")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RULE_NOT_FOUND"));
        // 规则已撤销
        redact("req-g8", "obs-1", "REDACTOR", "rule-v2", List.of(item("att-1", "n")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("RULE_REVOKED"));
        // 墓碑观测
        createObservation("req-g9", "obs-2").andExpect(status().isCreated());
        registerAttachment("req-g10", "obs-2", "REDACTOR", "att-2", "秘密").andExpect(status().isCreated());
        deleteObservation("req-g11", "obs-2", 1).andExpect(status().isOk());
        redact("req-g12", "obs-2", "REDACTOR", "rule-v1", List.of(item("att-2", "n")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OBSERVATION_TOMBSTONE"));
        // 全部失败均不产生版本
        assertThat(redactionCount("att-1")).isZero();
        assertThat(redactionCount("att-2")).isZero();
    }

    @Test
    void redactSameAttachmentSameRuleTwiceReturns409() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密数据");
        registerRule("req-h1", "REDACTOR", "rule-v1", List.of("秘密"), "***").andExpect(status().isCreated());
        redact("req-h2", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "n1")))
                .andExpect(status().isCreated());
        // 同一附件同一规则再次脱敏（不同 requestId）→ 409
        redact("req-h3", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "n2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REDACTION_RULE_ALREADY_APPLIED"));
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    // ---------- 公开查询与撤销 ----------

    @Test
    void publicQueryReturnsLatestActiveAndRevokeHidesIt() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密数据");
        registerRule("req-i1", "REDACTOR", "rule-a", List.of("秘密"), "AAA").andExpect(status().isCreated());
        registerRule("req-i2", "REDACTOR", "rule-b", List.of("数据"), "BBB").andExpect(status().isCreated());
        redact("req-i3", "obs-1", "REDACTOR", "rule-a", List.of(item("att-1", "v1")))
                .andExpect(status().isCreated());
        redact("req-i4", "obs-1", "REDACTOR", "rule-b", List.of(item("att-1", "v2")))
                .andExpect(status().isCreated());

        // 公开返回最新有效版本 v2
        publicAttachments("obs-1")
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].redactedSummary").value("秘密BBB"));

        // 撤销 v2 → 公开回退到 v1
        revokeRedaction("req-i5", "REDACTOR", "att-1", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAtUtc").isNotEmpty())
                .andExpect(jsonPath("$.revokeReason").value("撤销脱敏"));
        publicAttachments("obs-1")
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].redactedSummary").value("AAA数据"));

        // 撤销 v1 → 公开不再返回该附件
        revokeRedaction("req-i6", "REDACTOR", "att-1", 1).andExpect(status().isOk());
        publicAttachments("obs-1").andExpect(jsonPath("$").isEmpty());

        // 历史仍保留两个版本及撤销信息（证据权限）
        history("att-1", "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].revokeReason").value("撤销脱敏"))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].status").value("REVOKED"));
        assertThat(redactionCount("att-1")).isEqualTo(2);
    }

    @Test
    void revokeRedactionFailureBranches() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密");
        registerRule("req-j1", "REDACTOR", "rule-v1", List.of("秘密"), "***").andExpect(status().isCreated());
        redact("req-j2", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "n")))
                .andExpect(status().isCreated());

        // 角色不足
        revokeRedaction("req-j3", "PUBLIC", "att-1", 1).andExpect(status().isForbidden());
        // 不存在的脱敏版本
        revokeRedaction("req-j4", "REDACTOR", "att-1", 9)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REDACTION_NOT_FOUND"));
        revokeRedaction("req-j5", "REDACTOR", "att-x", 1)
                .andExpect(status().isNotFound());
        // 成功撤销
        revokeRedaction("req-j6", "REDACTOR", "att-1", 1).andExpect(status().isOk());
        // 重复撤销
        revokeRedaction("req-j7", "REDACTOR", "att-1", 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("REDACTION_ALREADY_REVOKED"));
    }

    // ---------- 物理清除 ----------

    @Test
    void purgeClearsOriginalAndIsIrreversible() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密数据");
        registerRule("req-p1", "REDACTOR", "rule-v1", List.of("秘密"), "***").andExpect(status().isCreated());
        redact("req-p2", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "n")))
                .andExpect(status().isCreated());

        // 非 ADMIN 不能清除
        purge("req-p3", "REDACTOR", "att-1").andExpect(status().isForbidden());
        // ADMIN 清除
        purge("req-p4", "ADMIN", "att-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentPurged").value(true))
                .andExpect(jsonPath("$.purgedAtUtc").isNotEmpty());
        // 原始摘要不可恢复
        evidence("att-1", "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentPurged").value(true))
                .andExpect(jsonPath("$.contentSummary").doesNotExist());
        // 清除后不能再脱敏
        redact("req-p5", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "n")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CONTENT_PURGED"));
        // 重复清除
        purge("req-p6", "ADMIN", "att-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CONTENT_PURGED"));
        // 既有脱敏版本不受清除影响，仍可撤销
        revokeRedaction("req-p7", "REDACTOR", "att-1", 1).andExpect(status().isOk());
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReplaysAndDifferentParamsConflict() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密数据");
        registerRule("req-k1", "REDACTOR", "rule-v1", List.of("秘密"), "***").andExpect(status().isCreated());

        // 同 requestId 同参重放：返回首次完整响应，不产生新版本
        redact("req-k2", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "n")))
                .andExpect(status().isCreated());
        redact("req-k2", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "n")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versions[0].version").value(1));
        assertThat(redactionCount("att-1")).isEqualTo(1);

        // 同 requestId 异参 → 409
        redact("req-k2", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "不同说明")))
                .andExpect(status().isConflict());
        assertThat(redactionCount("att-1")).isEqualTo(1);
    }

    // ---------- 合并快照固化引用 ----------

    @Test
    void mergeFreezesAttachmentRefsAndLaterRedactionDoesNotRewrite() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密数据");
        registerRule("req-m1", "REDACTOR", "rule-a", List.of("秘密"), "AAA").andExpect(status().isCreated());
        registerRule("req-m2", "REDACTOR", "rule-b", List.of("数据"), "BBB").andExpect(status().isCreated());
        redact("req-m3", "obs-1", "REDACTOR", "rule-a", List.of(item("att-1", "v1")))
                .andExpect(status().isCreated());

        // 合并生成新版本 v2，固化引用 att-1@1（脱敏版本1）
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-m4");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点B");
        mergeBody.put("reading", "2.0");
        mergeBody.put("note", "合并备注");
        mergeBody.put("attachmentRefs", List.of(Map.of("attachmentId", "att-1", "redactionVersion", 1)));
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 读取固化引用
        mockMvc.perform(get("/api/observations/{id}/versions/{v}/attachments", "obs-1", 2)
                        .param("role", "EVIDENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].summary").value("AAA数据"));

        // 后续脱敏生成版本 2：不追溯替换快照中的引用
        redact("req-m5", "obs-1", "REDACTOR", "rule-b", List.of(item("att-1", "v2")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/observations/{id}/versions/{v}/attachments", "obs-1", 2)
                        .param("role", "EVIDENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].summary").value("AAA数据"));
    }

    @Test
    void mergeRejectsAttachmentRefNotBelongingToObservation() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密");
        createObservation("req-n1", "obs-2").andExpect(status().isCreated());

        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-n2");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点B");
        mergeBody.put("reading", "2.0");
        mergeBody.put("note", "备注");
        mergeBody.put("attachmentRefs", List.of(Map.of("attachmentId", "att-1", "redactionVersion", 1)));
        // att-1 属于 obs-1，不属于 obs-2
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ATTACHMENT_OWNERSHIP"));
    }

    @Test
    void resolveFreezesAttachmentRefsOnNewVersion() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密数据");
        registerRule("req-s1", "REDACTOR", "rule-a", List.of("秘密"), "AAA").andExpect(status().isCreated());
        redact("req-s2", "obs-1", "REDACTOR", "rule-a", List.of(item("att-1", "v1")))
                .andExpect(status().isCreated());

        // 服务端推进到 v2（地点改站点B）
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-s3");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点B");
        mergeBody.put("reading", "1.0");
        mergeBody.put("note", "初始备注");
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());

        // 人工冲突解决：基于 v1、期望当前 v2，地点选 CANDIDATE（站点C），固化引用 att-1@1
        Map<String, Object> resolveBody = new LinkedHashMap<>();
        resolveBody.put("requestId", "req-s4");
        resolveBody.put("resolutionId", "res-s4");
        resolveBody.put("baseVersion", 1);
        resolveBody.put("expectedCurrentVersion", 2);
        resolveBody.put("location", "站点C");
        resolveBody.put("reading", "1.0");
        resolveBody.put("note", "初始备注");
        resolveBody.put("selections", Map.of("location", "CANDIDATE"));
        resolveBody.put("operator", "op-1");
        resolveBody.put("attachmentRefs", List.of(Map.of("attachmentId", "att-1", "redactionVersion", 1)));
        mockMvc.perform(post("/api/observations/{id}/resolve", "obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        // 解决生成的新版本 v3 固化了附件引用
        mockMvc.perform(get("/api/observations/{id}/versions/{v}/attachments", "obs-1", 3)
                        .param("role", "EVIDENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].summary").value("AAA数据"));
    }

    // ---------- 诊断查询 ----------

    @Test
    void diagnosticsReturnsCountsAndLeftClosedRightOpenRange() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密数据");
        registerRule("req-t1", "REDACTOR", "rule-a", List.of("秘密"), "AAA").andExpect(status().isCreated());
        registerRule("req-t2", "REDACTOR", "rule-b", List.of("数据"), "BBB").andExpect(status().isCreated());

        MvcResult r1 = redact("req-t3", "obs-1", "REDACTOR", "rule-a", List.of(item("att-1", "v1")))
                .andExpect(status().isCreated()).andReturn();
        String created1 = objectMapper.readTree(r1.getResponse().getContentAsString())
                .path("versions").get(0).path("createdAtUtc").asText();

        // 诊断：expectedCount 差额
        mockMvc.perform(get("/api/observations/{id}/redactions/diagnostics", "obs-1")
                        .param("role", "EVIDENCE").param("expectedCount", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualCount").value(1))
                .andExpect(jsonPath("$.expectedCount").value(5))
                .andExpect(jsonPath("$.difference").value(-4))
                .andExpect(jsonPath("$.activeCount").value(1))
                .andExpect(jsonPath("$.revokedCount").value(0));

        // 左闭右开：[created1, created1+1s) 包含该记录
        mockMvc.perform(get("/api/observations/{id}/redactions/diagnostics", "obs-1")
                        .param("role", "EVIDENCE")
                        .param("from", created1)
                        .param("to", "2099-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualCount").value(1));
        // 右开：(created1, ...) 不包含
        mockMvc.perform(get("/api/observations/{id}/redactions/diagnostics", "obs-1")
                        .param("role", "EVIDENCE")
                        .param("from", "2020-01-01T00:00:00Z")
                        .param("to", created1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualCount").value(0));

        // 非法区间
        mockMvc.perform(get("/api/observations/{id}/redactions/diagnostics", "obs-1")
                        .param("role", "EVIDENCE")
                        .param("from", "2021-01-01T00:00:00Z")
                        .param("to", "2020-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());

        // 撤销后计数变化
        revokeRedaction("req-t4", "REDACTOR", "att-1", 1).andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/{id}/redactions/diagnostics", "obs-1")
                        .param("role", "EVIDENCE"))
                .andExpect(jsonPath("$.activeCount").value(0))
                .andExpect(jsonPath("$.revokedCount").value(1));

        // 诊断需要证据权限
        mockMvc.perform(get("/api/observations/{id}/redactions/diagnostics", "obs-1")
                        .param("role", "PUBLIC"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicQueryOnTombstoneReturns404ButEvidenceHistoryRemains() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密数据");
        registerRule("req-w1", "REDACTOR", "rule-v1", List.of("秘密"), "***").andExpect(status().isCreated());
        redact("req-w2", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "n")))
                .andExpect(status().isCreated());
        deleteObservation("req-w3", "obs-1", 1).andExpect(status().isOk());

        // 墓碑观测不再公开返回附件
        publicAttachments("obs-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OBSERVATION_TOMBSTONE"));
        // 证据权限仍可读取原始附件与脱敏历史（历史不被删除）
        evidence("att-1", "EVIDENCE").andExpect(status().isOk());
        history("att-1", "EVIDENCE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1));
    }

    @Test
    void evidenceQueriesRequireEvidenceRole() throws Exception {
        setupObservationWithAttachment("obs-1", "att-1", "秘密");
        registerRule("req-u1", "REDACTOR", "rule-v1", List.of("秘密"), "***").andExpect(status().isCreated());
        redact("req-u2", "obs-1", "REDACTOR", "rule-v1", List.of(item("att-1", "n")))
                .andExpect(status().isCreated());

        evidence("att-1", "PUBLIC").andExpect(status().isForbidden());
        history("att-1", "PUBLIC").andExpect(status().isForbidden());
        redactionVersion("att-1", 1, "PUBLIC").andExpect(status().isForbidden());
        // EVIDENCE 可读
        evidence("att-1", "EVIDENCE").andExpect(status().isOk());
        history("att-1", "EVIDENCE").andExpect(status().isOk());
        redactionVersion("att-1", 1, "EVIDENCE").andExpect(status().isOk());
    }
}
