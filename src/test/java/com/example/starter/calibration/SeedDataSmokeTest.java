package com.example.starter.calibration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地入口合成数据冒烟测试：使用独立命名的 H2 内存库（不加 DB_CLOSE_DELAY，
 * 上下文关闭连接池后即释放），验证自动建表与 data.sql 合成数据完整加载，
 * 并在种子数据上跑通“查询阈值→读取预算→整批放行→可用结果”的合成调用链。
 * 不清理任何数据，避免与其他测试类共享的默认内存库互相影响。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seedcheck;MODE=MySQL;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class SeedDataSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void syntheticDataIsLoadedAndReleaseChainWorks() throws Exception {
        // 合成数据已自动初始化：1 张有效证书、阈值 v1、2 条测量、1 份两分量预算
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-DEMO'", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_threshold WHERE instrument_id = 'INS-DEMO'", Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE instrument_id = 'INS-DEMO'", Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM uncertainty_component c "
                        + "JOIN uncertainty_budget b ON b.id = c.budget_id "
                        + "JOIN measurement m ON m.id = b.measurement_id "
                        + "WHERE m.measurement_key = 'M-DEMO-2'", Long.class));

        mvc.perform(get("/api/thresholds/current").param("instrumentId", "INS-DEMO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.maxUncertainty").value("5"));

        // 种子预算 sqrt(3²+4²)=5.0000，阈值 5（含端点满足）
        mvc.perform(get("/api/measurements/{key}/budget", "M-DEMO-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetVersion").value(1))
                .andExpect(jsonPath("$.combinedUncertainty").value("5.0000"))
                .andExpect(jsonPath("$.withinThreshold").value(true))
                .andExpect(jsonPath("$.components[0].name").value("repeatability"))
                .andExpect(jsonPath("$.components[1].name").value("reference"));

        // 无预算测量（空预算合成值 0）与有预算测量同批放行均成功
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-DEMO-1\",\"M-DEMO-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released.length()").value(2));

        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(2));

        // 放行历史固化预算与阈值快照
        mvc.perform(get("/api/measurements/{key}", "M-DEMO-2"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releases[0].thresholdVersion").value(1))
                .andExpect(jsonPath("$.releases[0].combinedUncertainty").value("5.0000"));
    }
}
