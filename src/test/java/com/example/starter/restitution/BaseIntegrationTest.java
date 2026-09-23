package com.example.starter.restitution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 H2(MODE=MySQL) + 完整 Spring MVC 栈的集成测试基类；每个用例前清空全部业务表。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    private static final List<String> TABLES = List.of(
            "request_record", "frozen_approval", "frozen_evidence", "frozen_item", "frozen_claim",
            "approval", "evidence", "claim_item", "claim", "case_item", "restitution_case");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    protected String newRequestId() {
        return UUID.randomUUID().toString();
    }

    protected MvcResult writeOk(String method, String path, String actor, String requestId,
                                Object body, int expectedStatus) throws Exception {
        var request = switch (method) {
            case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON)
                    .content(body == null ? "" : objectMapper.writeValueAsString(body));
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        request.header("X-Actor-Id", actor).header("X-Request-Id", requestId);
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    protected JsonNode writeJson(String method, String path, String actor, String requestId,
                                 Object body, int expectedStatus) throws Exception {
        MvcResult result = writeOk(method, path, actor, requestId, body, expectedStatus);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }

    protected JsonNode getJson(String path, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(path)).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected String createCase(String actor, List<String> items) throws Exception {
        return writeJson("POST", "/api/cases", actor, newRequestId(),
                Map.of("items", items), 201).get("caseId").asText();
    }

    protected void registerClaim(String actor, String caseId, String claimKey,
                                 String applicant, List<String> items) throws Exception {
        writeJson("POST", "/api/cases/" + caseId + "/claims", actor, newRequestId(),
                Map.of("claimKey", claimKey, "applicant", applicant,
                        "statement", "desc-" + claimKey, "items", items), 201);
    }

    protected void addEvidence(String actor, String caseId, String claimKey,
                               String evidenceKey, String summary) throws Exception {
        writeJson("POST", "/api/cases/" + caseId + "/claims/" + claimKey + "/evidences",
                actor, newRequestId(),
                Map.of("evidenceKey", evidenceKey, "summary", summary), 201);
    }

    protected void approve(String reviewer, String caseId, String claimKey) throws Exception {
        writeOk("POST", "/api/cases/" + caseId + "/claims/" + claimKey + "/approvals",
                reviewer, newRequestId(), null, 201);
    }
}
