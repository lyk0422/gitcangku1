package com.example.starter.consent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验证本地运行入口（--spring.profiles.active=local）所加载的 schema-local.sql / data-local.sql
 * 与合成调用链可用：使用独立命名的 H2 内存库（MODE=MySQL），不依赖外部 MySQL 或 Docker。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:consent-localcheck;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=60000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.schema-locations=classpath:schema-local.sql",
        "spring.sql.init.data-locations=classpath:data-local.sql"
})
class LocalEntrySyntheticCallTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void syntheticCallChainOnSeededLocalDataSucceeds() throws Exception {
        // 1) 接收方 R 就 epoch 1 的 AUDIT 授权提交承诺，到期时刻为未来 1 小时（UTC）
        Instant expiry = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);
        mockMvc.perform(post("/api/v1/recipient/attestations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"local-a-1","recipientKey":"recipient-R","subjectKey":"subj-a",
                                 "purpose":"AUDIT","epoch":1,"expiresAt":"%s"}
                                """.formatted(expiry)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.epoch").value(1));

        // 2) R 在有效期内审计读取：只返回原有授权范围内的合成数据
        mockMvc.perform(get("/api/v1/recipient/records")
                        .param("recipientKey", "recipient-R")
                        .param("subjectKey", "subj-a")
                        .param("purpose", "AUDIT")
                        .param("epoch", "1")
                        .param("recordKey", "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("audit-payload-1"))
                .andExpect(jsonPath("$.purpose").value("AUDIT"));

        // 3) 授权方查看读取审计，能看到本次读取依据
        mockMvc.perform(get("/api/v1/consents/read-audits").param("subjectKey", "subj-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recipientKey").value("recipient-R"))
                .andExpect(jsonPath("$[0].recordKey").value("rec-1"));
    }
}
