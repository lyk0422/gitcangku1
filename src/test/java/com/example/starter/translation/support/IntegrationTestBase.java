package com.example.starter.translation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：真实 Spring 上下文 + H2（MySQL 兼容模式），每个用例前清空全部业务表，
 * 避免用例间顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM publication_translation");
        jdbc.update("DELETE FROM publication_segment");
        jdbc.update("DELETE FROM publication");
        jdbc.update("DELETE FROM translation_approval");
        jdbc.update("DELETE FROM translation");
        jdbc.update("DELETE FROM segment");
        jdbc.update("DELETE FROM document_language");
        jdbc.update("DELETE FROM document");
        jdbc.update("DELETE FROM request_log");
    }

    protected ResultActions postJson(String url, String actor, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post(url).contentType(MediaType.APPLICATION_JSON);
        if (actor != null) {
            request = request.header("X-Actor-Id", actor);
        }
        return mockMvc.perform(request.content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions getJson(String url) throws Exception {
        return mockMvc.perform(get(url));
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 建文档合成数据：单文档、指定语言与段落。
     */
    protected Map<String, Object> createDocBody(String requestId, String documentId,
                                                List<String> languages, List<Map<String, String>> segments) {
        return Map.of(
                "requestId", requestId,
                "documentId", documentId,
                "targetLanguages", languages,
                "segments", segments);
    }

    protected Map<String, String> seg(String segmentId, String sourceText) {
        return Map.of("segmentId", segmentId, "sourceText", sourceText);
    }

    protected ResultActions createDoc(String requestId, String documentId,
                                      List<String> languages, List<Map<String, String>> segments) throws Exception {
        return postJson("/api/documents", "alice", createDocBody(requestId, documentId, languages, segments));
    }

    protected ResultActions submitTranslation(String documentId, String segmentId, String requestId,
                                              String language, String body, int sourceVersion, String actor)
            throws Exception {
        return postJson("/api/documents/" + documentId + "/segments/" + segmentId + "/translations", actor,
                Map.of("requestId", requestId, "language", language, "body", body,
                        "sourceVersion", sourceVersion));
    }

    protected ResultActions approve(String documentId, String segmentId, String language, String requestId,
                                    int sourceVersion, int translationVersion, String actor) throws Exception {
        return postJson("/api/documents/" + documentId + "/segments/" + segmentId
                        + "/translations/" + language + "/approval", actor,
                Map.of("requestId", requestId, "sourceVersion", sourceVersion,
                        "translationVersion", translationVersion));
    }

    protected ResultActions reviseSource(String documentId, String segmentId, String requestId,
                                         String sourceText, String actor) throws Exception {
        return postJson("/api/documents/" + documentId + "/segments/" + segmentId + "/source", actor,
                Map.of("requestId", requestId, "sourceText", sourceText));
    }

    protected ResultActions publish(String documentId, String requestId,
                                    int expectedDraftVersion, int expectedPublishedVersion) throws Exception {
        return postJson("/api/documents/" + documentId + "/publications", "alice",
                Map.of("requestId", requestId, "expectedDraftVersion", expectedDraftVersion,
                        "expectedPublishedVersion", expectedPublishedVersion));
    }

    /**
     * 准备一份单段落单语言、已提交译文并批准的文档（草稿版本为 2）。
     */
    protected void prepareApprovableDoc(String documentId, String requestPrefix) throws Exception {
        createDoc(requestPrefix + "-c", documentId, List.of("en"), List.of(seg("s1", "Hello")));
        submitTranslation(documentId, "s1", requestPrefix + "-t", "en", "Hello EN", 1, "bob");
        approve(documentId, "s1", "en", requestPrefix + "-a", 1, 1, "carol");
    }
}
