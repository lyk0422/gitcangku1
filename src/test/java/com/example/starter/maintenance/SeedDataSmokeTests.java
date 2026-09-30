package com.example.starter.maintenance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 本地运行入口烟雾测试：使用独立命名的 H2 内存库启动完整应用上下文，
 * 由 schema.sql 自动建表、data.sql 初始化合成种子（设备 D：有效读数 100 小时、阈值 10 小时），
 * 不做任何清理，验证“启动即得”的合成调用链；上下文关闭后内存库随 JVM 释放。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:maintenance_meter_seed_smoke;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@AutoConfigureMockMvc
class SeedDataSmokeTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void seededDeviceD_pendingWitnessFlow() throws Exception {
        // 种子已就绪：设备 D 有效读数 100 小时（6000 分钟），周期 6500 分钟，阈值 600 分钟；当前 OK
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(6500))
                .andExpect(jsonPath("$.revisionThresholdMinutes").value(600))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(6000))
                .andExpect(jsonPath("$.runMinutes").value(6000))
                .andExpect(jsonPath("$.status").value("OK"));

        // 合成调用：alice 申请改为 112 小时（6720 分钟），差额 720 > 阈值 600 → 待确认
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"seed-rev-112","expectedVersion":2,"cumulativeMinutes":6720,
                                 "submitterId":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(6000))
                .andExpect(jsonPath("$.pendingCumulativeMinutes").value(6720))
                .andExpect(jsonPath("$.pendingDeltaMinutes").value(720))
                .andExpect(jsonPath("$.pendingThresholdMinutes").value(600));

        // 见证前周期仍按 100 小时解释
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(6000))
                .andExpect(jsonPath("$.status").value("OK"));

        // bob 见证同一读数版本（基准修订号 1）→ 112 小时生效，周期判定转为 DUE
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"seed-wit-bob","expectedVersion":3,"witnessId":"bob",
                                 "baseRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(6720))
                .andExpect(jsonPath("$.revisionNo").value(2));
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(6720))
                .andExpect(jsonPath("$.runMinutes").value(6720))
                .andExpect(jsonPath("$.status").value("DUE"));
    }
}
