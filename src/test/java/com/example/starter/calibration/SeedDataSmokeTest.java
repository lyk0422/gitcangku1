package com.example.starter.calibration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地入口冒烟：独立命名的 H2 内存库（MODE=MySQL）启动时自动建表并初始化 data.sql 合成数据，
 * 随后用一条放行调用走通“证书+阈值+测量+RSS 预算→放行快照”主流程。
 * 上下文由测试框架管理、随测试结束关闭，不是常驻外部服务。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:calibration_seed;MODE=MySQL;DB_CLOSE_DELAY=-1")
class SeedDataSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seedDataLoadedAndSyntheticReleaseSucceeds() throws Exception {
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-SEED'",
                Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM instrument_threshold_version WHERE instrument_id = 'INS-SEED'",
                Long.class));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM measurement WHERE measurement_key = 'M-SEED'", String.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component uc JOIN measurement m ON m.id = uc.measurement_id "
                        + "WHERE m.measurement_key = 'M-SEED' AND uc.budget_version = 1", Long.class));

        // 合成调用：carol 放行 M-SEED；预算 u_ref=0.4、u_rep=0.3 → RSS 0.5000 ≤ 阈值 1
        String firstBody = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .header("X-Request-Id", "SEED-RELEASE-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-SEED\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released[0]").value("M-SEED"))
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.items[0].combinedUncertainty").value("0.5000"))
                .andExpect(jsonPath("$.items[0].thresholdValue").value("1"))
                .andExpect(jsonPath("$.items[0].budgetVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        String firstBatchId = (String) com.jayway.jsonpath.JsonPath.read(firstBody, "$.batchId");

        // 同参重放：原样返回首次响应快照（200、同 batchId），不重新裁决、不新增放行记录
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .header("X-Request-Id", "SEED-RELEASE-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-SEED\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(firstBatchId));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class),
                "同参重放不得新增放行记录");
    }
}
