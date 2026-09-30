package com.example.starter.web;

import com.example.starter.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 业务入口端到端测试：真实 Spring MVC + H2，校验状态码、原因与 UTC 时间序列化。
 */
class AllocationControllerIntegrationTest extends AbstractIntegrationTest {

    private MockMvc mockMvc;

    AllocationControllerIntegrationTest(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void fullScenarioOverHttp() throws Exception {
        // 1) 申请 20:00-21:00
        String primaryLocation = applyAndReturnRequestNo("""
                {"project":"P-primary","instrumentId":"T","researchGroup":"G1",
                 "windowStart":"2026-10-01T20:00:00Z","windowEnd":"2026-10-01T21:00:00Z",
                 "durationMinutes":60,"requestId":"http-1"}""");

        // 2) 管理员批准
        mockMvc.perform(post("/api/requests/{no}/approval", primaryLocation)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.allocatedAt").exists());

        // 3) 候补 20:10-20:50 申请
        String waiterNo = applyAndReturnRequestNo("""
                {"project":"P-wait","instrumentId":"T","researchGroup":"G2",
                 "windowStart":"2026-10-01T20:10:00Z","windowEnd":"2026-10-01T20:50:00Z",
                 "durationMinutes":40}""");

        // 4) 重叠批准 409 且指出原因
        mockMvc.perform(post("/api/requests/{no}/approval", waiterNo)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重叠")));

        // 5) 其他研究组撤销 409
        mockMvc.perform(post("/api/requests/{no}/cancellation", primaryLocation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"researchGroup":"G2"}"""))
                .andExpect(status().isConflict());

        // 6) 归属组撤销 200，候补自动接替
        mockMvc.perform(post("/api/requests/{no}/cancellation", primaryLocation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"researchGroup":"G1","requestId":"cancel-http-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/requests/{no}", waiterNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.promotedFromRequestNo").value(primaryLocation));

        // 7) 时间轴含已分配窗口、候补顺序与接替来源
        mockMvc.perform(get("/api/requests").param("instrumentId", "T"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocated[0].requestNo").value(waiterNo))
                .andExpect(jsonPath("$.successions[0].vacatedRequestNo").value(primaryLocation))
                .andExpect(jsonPath("$.successions[0].promotedRequestNo").value(waiterNo));

        // 8) 重复撤销重放首次结果（同 requestId），不再产生接替流水
        mockMvc.perform(post("/api/requests/{no}/cancellation", primaryLocation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"researchGroup":"G1","requestId":"cancel-http-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/requests").param("instrumentId", "T"))
                .andExpect(jsonPath("$.successions.length()").value(1));
    }

    @Test
    void invalidWindowReturns422OverHttp() throws Exception {
        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"project":"P","instrumentId":"T","researchGroup":"G",
                                 "windowStart":"2026-10-01T21:00:00Z","windowEnd":"2026-10-01T20:00:00Z",
                                 "durationMinutes":60}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("结束时刻")));
    }

    @Test
    void missingRequiredFieldReturns422OverHttp() throws Exception {
        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"project":"P","windowStart":"2026-10-01T20:00:00Z",
                                 "windowEnd":"2026-10-01T21:00:00Z","durationMinutes":60}"""))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownRequestReturns404OverHttp() throws Exception {
        mockMvc.perform(get("/api/requests/{no}", "REQ-000999"))
                .andExpect(status().isNotFound());
    }

    private String applyAndReturnRequestNo(String body) throws Exception {
        String response = mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.requestNo");
    }
}
