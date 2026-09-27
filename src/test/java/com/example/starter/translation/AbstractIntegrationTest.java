package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import com.example.starter.translation.service.BusinessClock;

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
    protected BusinessClock clock;

    @BeforeEach
    void cleanTables() {
        clock.reset();
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM conflict_event");
        jdbc.update("DELETE FROM conflict_declaration");
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
        return perform(MockMvcRequestBuilders.post(url), body, null, null);
    }

    protected ApiResult postJson(String url, String body, String actorId) throws Exception {
        return perform(MockMvcRequestBuilders.post(url), body, actorId, null);
    }

    protected ApiResult postJson(String url, String body, String actorId, String actorRole) throws Exception {
        return perform(MockMvcRequestBuilders.post(url), body, actorId, actorRole);
    }

    protected ApiResult putJson(String url, String body) throws Exception {
        return perform(MockMvcRequestBuilders.put(url), body, null, null);
    }

    protected ApiResult putJson(String url, String body, String actorId) throws Exception {
        return perform(MockMvcRequestBuilders.put(url), body, actorId, null);
    }

    protected ApiResult getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(url)).andReturn();
        return toApiResult(result);
    }

    private ApiResult perform(MockHttpServletRequestBuilder builder, String body, String actorId,
                              String actorRole) throws Exception {
        builder.contentType("application/json").content(body);
        if (actorId != null) {
            builder.header("X-Actor-Id", actorId);
        }
        if (actorRole != null) {
            builder.header("X-Actor-Role", actorRole);
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
                + ",\"reason\":\"" + reason + "\"}";
        return postJson("/api/documents/" + documentId + "/segments/" + segmentId + "/translations/" + language
                + "/reject", body, actorId);
    }

    /** 批量登记冲突：rangesJson 为范围数组，声明人取 actorId。 */
    protected ApiResult registerConflicts(long documentId, String actorId, String rangesJson,
                                          String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"ranges\":" + rangesJson + "}";
        return postJson("/api/documents/" + documentId + "/conflicts", body, actorId);
    }

    protected ApiResult resolveConflict(long documentId, long conflictId, String actorId, String actorRole,
                                        String reason, String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"reason\":\"" + reason + "\"}";
        return postJson("/api/documents/" + documentId + "/conflicts/" + conflictId + "/resolve",
                body, actorId, actorRole);
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
