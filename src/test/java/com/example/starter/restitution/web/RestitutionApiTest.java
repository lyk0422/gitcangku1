package com.example.starter.restitution.web;

import com.example.starter.restitution.AbstractH2IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 HTTP 入口端到端测试：经 MockMvc 走完整 Controller/Service/H2 链路，
 * 校验 X-Actor-Id、X-Request-Id 头语义与各状态码。
 */
class RestitutionApiTest extends AbstractH2IntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @PostConstruct
    void init() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    private static String json(Object body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
    }

    @Test
    void fullHappyFlowThroughHttpEndpointsFreezesDecision() throws Exception {
        // 1. 建案
        MvcResult caseResult = mockMvc.perform(post("/api/cases")
                        .header("X-Actor-Id", "officer")
                        .header("X-Request-Id", "http-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("artifactNos", List.of("A", "B")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        String caseKey = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(caseResult.getResponse().getContentAsString()).get("caseKey").asText();

        // 2. 登记两个可拼接覆盖的主张
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "http-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("claimKey", "CL-1", "applicant", "alice",
                                "statement", "家族藏品 A", "artifactNos", List.of("A")))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "http-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("claimKey", "CL-2", "applicant", "bob",
                                "statement", "家族藏品 B", "artifactNos", List.of("B")))))
                .andExpect(status().isCreated());

        // 3. 证据
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-1/evidence")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "http-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("evidenceKey", "E-1", "summary", "档案一"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-2/evidence")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "http-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("evidenceKey", "E-2", "summary", "档案二"))))
                .andExpect(status().isCreated());

        // 4. 两名不同评审人分别批准
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-1/approvals")
                        .header("X-Actor-Id", "r1").header("X-Request-Id", "http-6"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-1/approvals")
                        .header("X-Actor-Id", "r2").header("X-Request-Id", "http-7"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-2/approvals")
                        .header("X-Actor-Id", "r1").header("X-Request-Id", "http-8"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-2/approvals")
                        .header("X-Actor-Id", "r2").header("X-Request-Id", "http-9"))
                .andExpect(status().isNoContent());

        long currentVersion = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(mockMvc.perform(get("/api/cases/" + caseKey)).andReturn()
                        .getResponse().getContentAsString()).get("version").asLong();

        // 5. 裁决
        mockMvc.perform(post("/api/cases/" + caseKey + "/decisions")
                        .header("X-Actor-Id", "judge").header("X-Request-Id", "http-10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", currentVersion,
                                "claimKeys", List.of("CL-1", "CL-2")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.version").value(currentVersion + 1))
                .andExpect(jsonPath("$.artifacts[0].artifactNo").value("A"))
                .andExpect(jsonPath("$.artifacts[0].applicant").value("alice"))
                .andExpect(jsonPath("$.artifacts[1].artifactNo").value("B"))
                .andExpect(jsonPath("$.artifacts[1].applicant").value("bob"));

        // 6. 历史查询读快照不重算
        mockMvc.perform(get("/api/cases/" + caseKey + "/decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.approvals.length()").value(4));

        // 7. 终态拒绝新写入
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-1/evidence")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "http-11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("evidenceKey", "E-9", "summary", "终态后证据"))))
                .andExpect(status().isConflict());
    }

    @Test
    void decision422ReturnsStructuredDetails() throws Exception {
        MvcResult caseResult = mockMvc.perform(post("/api/cases")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "e-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("artifactNos", List.of("A", "B")))))
                .andExpect(status().isCreated()).andReturn();
        String caseKey = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(caseResult.getResponse().getContentAsString()).get("caseKey").asText();

        mockMvc.perform(post("/api/cases/" + caseKey + "/claims")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "e-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("claimKey", "CL-1", "applicant", "alice",
                                "statement", "说明", "artifactNos", List.of("A")))))
                .andExpect(status().isCreated());

        // 无证据、无批准、缺少藏品 B：裁决 422 并返回缺项集合。
        mockMvc.perform(post("/api/cases/" + caseKey + "/decisions")
                        .header("X-Actor-Id", "judge").header("X-Request-Id", "e-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 2, "claimKeys", List.of("CL-1")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.claimDeficiencies.CL-1[0]").value("current_approval"))
                .andExpect(jsonPath("$.details.missingArtifacts[0]").value("B"));
    }

    @Test
    void selfApprovalIs403AndMissingRequestHeaderIs400() throws Exception {
        MvcResult caseResult = mockMvc.perform(post("/api/cases")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "h-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("artifactNos", List.of("A")))))
                .andExpect(status().isCreated()).andReturn();
        String caseKey = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(caseResult.getResponse().getContentAsString()).get("caseKey").asText();
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "h-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("claimKey", "CL-1", "applicant", "alice",
                                "statement", "说明", "artifactNos", List.of("A")))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-1/evidence")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "h-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("evidenceKey", "E-1", "summary", "摘要"))))
                .andExpect(status().isCreated());

        // 自批 403
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-1/approvals")
                        .header("X-Actor-Id", "alice").header("X-Request-Id", "h-4"))
                .andExpect(status().isForbidden());

        // 缺 X-Request-Id 400
        mockMvc.perform(post("/api/cases/" + caseKey + "/claims/CL-1/approvals")
                        .header("X-Actor-Id", "r1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotentReplayReturnsFirstResultAndDifferentParams409() throws Exception {
        String firstBody = mockMvc.perform(post("/api/cases")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("artifactNos", List.of("A")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 同键同参重放：相同状态码与相同响应体。
        mockMvc.perform(post("/api/cases")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("artifactNos", List.of("A")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseKey").value(
                        com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                                .readTree(firstBody).get("caseKey").asText()));

        // 同键异参 409
        mockMvc.perform(post("/api/cases")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("artifactNos", List.of("B")))))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownCaseAndMalformedBodyReturn404And400() throws Exception {
        mockMvc.perform(get("/api/cases/C-ghost")).andExpect(status().isNotFound());

        mockMvc.perform(post("/api/cases")
                        .header("X-Actor-Id", "officer").header("X-Request-Id", "bad-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not-json"))
                .andExpect(status().isBadRequest());
    }
}
