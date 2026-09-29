package com.example.starter.consent.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 本地入口（local profile）冒烟测试：验证 H2（MODE=MySQL）自动建表与合成数据初始化，
 * 以及一条端到端合成受限查询。使用独立内存库且不带 DB_CLOSE_DELAY，上下文关闭即释放。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:consent-local-smoke;MODE=MySQL;LOCK_TIMEOUT=30000"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@DirtiesContext
class LocalProfileSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void localProfileCreatesTablesSeedsDataAndServesSyntheticQuery() throws Exception {
        // 合成数据：demo-subject / RESEARCH / epoch1 额度 10
        mockMvc.perform(get("/api/v1/consents/diagnostics")
                        .param("subjectKey", "demo-subject")
                        .param("purpose", "RESEARCH")
                        .param("epoch", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantExists").value(true))
                .andExpect(jsonPath("$.grantStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.quotaStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(10))
                .andExpect(jsonPath("$.ledgerCount").value(0));

        // 一条合成业务调用：查询 rec-a（实际 1 个对象），余额 10 -> 9
        mockMvc.perform(post("/api/v1/consents/restricted-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"demo-query-1","subjectKey":"demo-subject","purpose":"RESEARCH",
                                 "epoch":1,"objects":[{"recordKey":"rec-a","count":1}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceBefore").value(10))
                .andExpect(jsonPath("$.balanceAfter").value(9))
                .andExpect(jsonPath("$.objectCount").value(1))
                .andExpect(jsonPath("$.results[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$.results[0].payload").value("demo-payload-a"));

        mockMvc.perform(get("/api/v1/consents/diagnostics")
                        .param("subjectKey", "demo-subject")
                        .param("purpose", "RESEARCH")
                        .param("epoch", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(9))
                .andExpect(jsonPath("$.queryCount").value(1))
                .andExpect(jsonPath("$.totalDeducted").value(1));
    }
}
