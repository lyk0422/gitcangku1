package com.example.starter.consent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 本地 H2 入口（local profile）冒烟测试：验证 schema-h2.sql 自动建表、data-h2.sql 合成种子
 * （AUDIT 前 3 代已撤回、第 4 代有效）可正常加载，并可在该入口完成承诺与读取闭环。
 */
@SpringBootTest(properties = "spring.profiles.active=local")
@AutoConfigureMockMvc
class LocalProfileSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void localH2ProfileSeedsEpoch4AndServesAttestedRead() throws Exception {
        // 合成种子数据已随建表初始化
        Integer activeEpoch = jdbc.queryForObject(
                "SELECT epoch FROM consent_grant WHERE subject_key = 'demo-subject' AND purpose = 'AUDIT'"
                        + " AND status = 'ACTIVE'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(activeEpoch).isEqualTo(4);

        String expiresAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plus(1, ChronoUnit.HOURS));
        mockMvc.perform(post("/api/v1/recipient/attestations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"local-a-1","recipientKey":"recipient-R","subjectKey":"demo-subject",
                                "purpose":"AUDIT","epoch":4,"expiresAt":"%s"}
                                """.formatted(expiresAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/recipient/records")
                        .param("recipientKey", "recipient-R")
                        .param("subjectKey", "demo-subject")
                        .param("purpose", "AUDIT")
                        .param("recordKey", "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(4))
                .andExpect(jsonPath("$.payload").value("audit-only-payload-epoch-4"));
    }
}
