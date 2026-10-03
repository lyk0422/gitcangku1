package com.example.starter.calibration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地入口冒烟测试：验证主配置同款 H2 内存库（MODE=MySQL）自动建表并加载 data.sql 合成数据，
 * 且演示批次可完成“离群检测→双人复核→放行”主流程。
 * 使用独立命名库（smoke），上下文关闭后数据库随之释放。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:smoke;MODE=MySQL;LOCK_TIMEOUT=10000",
        "spring.sql.init.data-locations=classpath:data.sql"
})
@AutoConfigureMockMvc
class LocalEntrySmokeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void syntheticDataLoadedAndDemoFlowWorks() throws Exception {
        // data.sql 合成数据已加载：演示证书与演示批次
        Integer certs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-DEMO'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, certs);
        mvc.perform(get("/api/batches/{key}", "BATCH-DEMO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetValue").value("10"))
                .andExpect(jsonPath("$.allowedDeviation").value("0.5"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));

        // 合成调用示例：提交越限读数 → 自动生成离群 → 阻断放行 → 双人 ACCEPT → 放行成功
        String body = mvc.perform(post("/api/batches/{key}/readings", "BATCH-DEMO")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"SMOKE-1","readingKey":"RD-SMOKE-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","value":"10.9","submittedBy":"alice"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlierId").isNumber())
                .andReturn().getResponse().getContentAsString();
        long outlierId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.outlierId")).longValue();

        mvc.perform(post("/api/batches/{key}/release", "BATCH-DEMO")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"SMOKE-2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTLIERS_PENDING"));

        for (String reviewer : new String[]{"bob", "carol"}) {
            mvc.perform(post("/api/outliers/{id}/reviews", outlierId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"SMOKE-RV-%s","reviewer":"%s","decision":"ACCEPT",
                                     "reason":"演示复核","batchRevision":1,"certificateVersion":1}
                                    """.formatted(reviewer, reviewer)))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/batches/{key}/release", "BATCH-DEMO")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"SMOKE-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.includedCount").value(1))
                .andExpect(jsonPath("$.meanValue").value("10.9"));
    }
}