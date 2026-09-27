package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.starter.translation.service.UtcClock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Clock;
import java.util.UUID;

/**
 * 集成测试基类：独立 H2 内存库（translation_test），每个用例前清空全部业务表，
 * 避免用例间顺序依赖；测试上下文关闭时连接池关闭，内存库随之释放。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UtcClock clock;

    @BeforeEach
    void cleanTables() {
        clock.setClock(Clock.systemUTC());
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM conflict_history");
        jdbc.update("DELETE FROM reviewer_conflict");
        jdbc.update("DELETE FROM translation");
        jdbc.update("DELETE FROM segment");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM term_rule");
        jdbc.update("DELETE FROM term_version");
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM document");
    }

    protected String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** POST JSON，返回响应状态与解析后的响应体。 */
    protected ApiResult postJson(String url, String body) throws Exception {
        return perform(MockMvcRequestBuilders.post(url), body, null);
    }

    protected ApiResult postJson(String url, String body, String actorId) throws Exception {
        return perform(MockMvcRequestBuilders.post(url), body, actorId);
    }

    protected ApiResult putJson(String url, String body) throws Exception {
        return perform(MockMvcRequestBuilders.put(url), body, null);
    }

    protected ApiResult putJson(String url, String body, String actorId) throws Exception {
        return perform(MockMvcRequestBuilders.put(url), body, actorId);
    }

    protected ApiResult getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(url)).andReturn();
        return toApiResult(result);
    }

    private ApiResult perform(MockHttpServletRequestBuilder builder, String body, String actorId)
            throws Exception {
        builder.contentType("application/json").content(body);
        if (actorId != null) {
            builder.header("X-Actor-Id", actorId);
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        return toApiResult(result);
    }

    private ApiResult toApiResult(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        JsonNode json = content.isBlank() ? null : objectMapper.readTree(content);
        return new ApiResult(status, json);
    }

    /** 建文档并返回 documentId。 */
    protected long createDocument(String requestId, String targetLanguagesJson, String segmentsJson)
            throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":" + targetLanguagesJson
                + ",\"segments\":" + segmentsJson + "}";
        ApiResult result = postJson("/api/documents", body);
        if (result.status() != 201) {
            throw new IllegalStateException("建文档失败: " + result.status() + " " + result.body());
        }
        return result.body().get("documentId").asLong();
    }

    /** 建文档（指定法定人数）并返回 documentId。 */
    protected long createDocument(String requestId, String targetLanguagesJson, String segmentsJson,
                                  int requiredReviewCount) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":" + targetLanguagesJson
                + ",\"segments\":" + segmentsJson + ",\"requiredReviewCount\":" + requiredReviewCount + "}";
        ApiResult result = postJson("/api/documents", body);
        if (result.status() != 201) {
            throw new IllegalStateException("建文档失败: " + result.status() + " " + result.body());
        }
        return result.body().get("documentId").asLong();
    }

    protected ApiResult submitTranslation(long documentId, String segmentId, String language, String actorId,
                                          String content, int sourceVersion, String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"content\":\"" + content
                + "\",\"sourceVersion\":" + sourceVersion + "}";
        return putJson("/api/documents/" + documentId + "/segments/" + segmentId + "/translations/" + language,
                body, actorId);
    }

    protected ApiResult approve(long documentId, String segmentId, String language, String actorId,
                                int translationVersion, String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"translationVersion\":" + translationVersion + "}";
        return postJson("/api/documents/" + documentId + "/segments/" + segmentId + "/translations/" + language
                + "/approve", body, actorId);
    }

    protected ApiResult reject(long documentId, String segmentId, String language, String actorId,
                               int translationVersion, String reason, String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"translationVersion\":" + translationVersion
                + ",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
        return postJson("/api/documents/" + documentId + "/segments/" + segmentId + "/translations/" + language
                + "/reject", body, actorId);
    }

    /** 登记单条冲突声明，区间为 UTC 毫秒、左闭右开。 */
    protected ApiResult declareConflict(long documentId, String actorId, String language,
                                        long validFrom, long validTo, String reason, String requestId)
            throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"language\":\"" + language + "\",\"validFrom\":"
                + validFrom + ",\"validTo\":" + validTo + ",\"reason\":\"" + reason + "\"}";
        return postJson("/api/documents/" + documentId + "/conflicts", body, actorId);
    }

    /** 批量登记冲突声明，declarationsJson 为声明对象数组。 */
    protected ApiResult declareConflictsBatch(long documentId, String actorId, String declarationsJson,
                                              String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"declarations\":" + declarationsJson + "}";
        return postJson("/api/documents/" + documentId + "/conflicts/batch", body, actorId);
    }

    /** 解除冲突；actorId 为合规确认人，角色须为 COMPLIANCE。 */
    protected ApiResult liftConflict(long conflictId, String actorId, String role, String liftReason,
                                     String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"liftReason\":"
                + (liftReason == null ? "null" : "\"" + liftReason + "\"") + "}";
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/documents/conflicts/" + conflictId + "/lift")
                        .contentType("application/json").content(body)
                        .header("X-Actor-Id", actorId).header("X-Actor-Role", role)).andReturn();
        return toApiResult(result);
    }

    protected ApiResult getConflicts(long documentId) throws Exception {
        return getJson("/api/documents/" + documentId + "/conflicts");
    }

    protected ApiResult getConflictHistory(long documentId) throws Exception {
        return getJson("/api/documents/" + documentId + "/conflicts/history");
    }

    protected ApiResult getReviewDiagnostics(long documentId) throws Exception {
        return getJson("/api/documents/" + documentId + "/reviews/diagnostics");
    }

    /** 将业务 UTC 时钟固定到指定毫秒，保证冲突命中、声明/解除时刻可控。 */
    protected void fixClock(long epochMilli) {
        clock.setClock(Clock.fixed(java.time.Instant.ofEpochMilli(epochMilli), java.time.ZoneOffset.UTC));
    }

    protected ApiResult publish(long documentId, int expectedDraftVersion, int expectedPublishedVersion,
                                String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"expectedDraftVersion\":" + expectedDraftVersion
                + ",\"expectedPublishedVersion\":" + expectedPublishedVersion + "}";
        return postJson("/api/documents/" + documentId + "/publish", body);
    }

    /** 新增术语版本：rulesJson 为规则数组 JSON。 */
    protected ApiResult updateTerms(long documentId, int expectedTermVersion, String rulesJson,
                                    String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"expectedTermVersion\":" + expectedTermVersion
                + ",\"rules\":" + rulesJson + "}";
        return putJson("/api/documents/" + documentId + "/terms", body);
    }

    /** HTTP 响应结果：状态码与 JSON 响应体。 */
    protected record ApiResult(int status, JsonNode body) {
    }
}
