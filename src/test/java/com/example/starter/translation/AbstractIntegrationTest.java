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

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM release_vote_freeze");
        jdbc.update("DELETE FROM review_vote");
        jdbc.update("DELETE FROM review_policy_reviewer");
        jdbc.update("DELETE FROM review_policy_stage");
        jdbc.update("DELETE FROM review_policy_current");
        jdbc.update("DELETE FROM review_policy");
        jdbc.update("DELETE FROM approval");
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

    /** 配置评审策略：languageQuorum/languageReviewers 与 complianceQuorum/complianceReviewers 为两阶段配置。 */
    protected ApiResult configurePolicy(long documentId, String language, int expectedPolicyVersion,
                                        int languageQuorum, String languageReviewersJson,
                                        int complianceQuorum, String complianceReviewersJson,
                                        String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"expectedPolicyVersion\":" + expectedPolicyVersion
                + ",\"languageStage\":{\"quorum\":" + languageQuorum + ",\"reviewers\":" + languageReviewersJson
                + "},\"complianceStage\":{\"quorum\":" + complianceQuorum + ",\"reviewers\":"
                + complianceReviewersJson + "}}";
        return putJson("/api/documents/" + documentId + "/review-policies/" + language, body);
    }

    /** 投票/改票：expectedVoteVersion 为 null 时省略该字段（等价于 0）。 */
    protected ApiResult castVote(long documentId, String segmentId, String language, String actorId,
                                 String voteKey, String stage, String decision,
                                 int sourceVersion, int translationVersion, int termVersion, int policyVersion,
                                 Integer expectedVoteVersion, String requestId) throws Exception {
        StringBuilder body = new StringBuilder("{\"requestId\":\"").append(requestId)
                .append("\",\"voteKey\":\"").append(voteKey)
                .append("\",\"stage\":\"").append(stage)
                .append("\",\"decision\":\"").append(decision)
                .append("\",\"sourceVersion\":").append(sourceVersion)
                .append(",\"translationVersion\":").append(translationVersion)
                .append(",\"termVersion\":").append(termVersion)
                .append(",\"policyVersion\":").append(policyVersion);
        if (expectedVoteVersion != null) {
            body.append(",\"expectedVoteVersion\":").append(expectedVoteVersion);
        }
        body.append('}');
        return postJson("/api/documents/" + documentId + "/segments/" + segmentId + "/translations/" + language
                + "/votes", body.toString(), actorId);
    }

    /** HTTP 响应结果：状态码与 JSON 响应体。 */
    protected record ApiResult(int status, JsonNode body) {
    }
}
