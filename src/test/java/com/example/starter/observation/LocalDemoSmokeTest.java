package com.example.starter.observation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地入口冒烟测试：启用 local profile，验证启动时合成数据写入、附件登记、脱敏与公开查询完整链路。
 * 使用独立命名内存库，与常规测试库隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:observation_local_smoke;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class LocalDemoSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void demoDataSeedsRedactedPublicView() throws Exception {
        // demo-att-1 已按 rule-v1 脱敏：公开视图返回受限展示摘要
        mockMvc.perform(get("/api/observations/{id}/attachments", "demo-obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value("demo-att-1"))
                .andExpect(jsonPath("$[0].redacted").value(true))
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].ruleVersion").value("rule-v1"))
                .andExpect(jsonPath("$[0].summary")
                        .value("[REDACTED rule=rule-v1] 人员证件号已替换为 ***"))
                // 第二附件未脱敏：公开视图返回原始摘要
                .andExpect(jsonPath("$[1].attachmentId").value("demo-att-2"))
                .andExpect(jsonPath("$[1].redacted").value(false))
                .andExpect(jsonPath("$[1].summary").value("音频转写：负责人张三，电话 13800001111"));
        // 证据读取者可按版本读原文与脱敏文
        mockMvc.perform(get("/api/observations/{id}/attachments/{att}/evidence", "demo-obs-1", "demo-att-1")
                        .header("X-Role", "EVIDENCE_READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalSummary")
                        .value("巡检照片：井口编号 JK-01，人员身份证 110101199001011234，坐标 39.9087,116.3975"))
                .andExpect(jsonPath("$.redactions[0].redactionVersion").value(1));
    }
}
