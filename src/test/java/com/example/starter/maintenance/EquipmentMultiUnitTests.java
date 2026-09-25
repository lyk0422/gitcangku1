package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 多计量单位换算与保养判定统一测试（真实 H2 内存库，MySQL 兼容模式）：
 * 覆盖单位登记、跨单位提交换算留痕、换算分钟口径单调性、判定统一、展示单位切换与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentMultiUnitTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM reading_conversion");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void registerHours(String equipmentId, String periodValue) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","measurementUnit":"HOURS",
                                 "maintenancePeriodValue":%s}
                                """.formatted(equipmentId, equipmentId, periodValue)))
                .andExpect(status().isCreated());
    }

    // ---------- 登记：单位声明与换算 ----------

    @Test
    void register_hoursUnit_convertsPeriodToMinutes() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-h1","equipmentId":"eq-h1","measurementUnit":"HOURS",
                                 "maintenancePeriodValue":2.5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementUnit").value("HOURS"))
                .andExpect(jsonPath("$.maintenancePeriodValue").value(2.5))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(150))
                .andExpect(jsonPath("$.version").value(1));

        // 设备单位配置查询（只读）
        mockMvc.perform(get("/api/equipment/eq-h1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementUnit").value("HOURS"))
                .andExpect(jsonPath("$.maintenancePeriodValue").value(2.5))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(150));
    }

    @Test
    void register_defaultUnitIsMinutes_legacyFieldCompatible() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-m1","equipmentId":"eq-m1","maintenancePeriodMinutes":100}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementUnit").value("MINUTES"))
                .andExpect(jsonPath("$.maintenancePeriodValue").value(100))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(100));
    }

    @Test
    void register_invalidUnitOrScale_rejected() throws Exception {
        // 非法单位 → 400
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-bad-u","equipmentId":"eq-bad-u",
                                 "measurementUnit":"DAYS","maintenancePeriodValue":1}
                                """))
                .andExpect(status().isBadRequest());
        // 超过 2 位小数 → 400
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-bad-s","equipmentId":"eq-bad-s",
                                 "measurementUnit":"HOURS","maintenancePeriodValue":1.555}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALUE_SCALE_EXCEEDED"));
        // 未提供任何周期值 → 400
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-bad-n","equipmentId":"eq-bad-n"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERIOD_REQUIRED"));
    }

    // ---------- 跨单位读数提交与换算留痕 ----------

    @Test
    void addReading_crossUnit_convertsAndRecordsTrace() throws Exception {
        registerHours("eq-xu", "2");

        // 以 MINUTES 提交 90 分钟 → 换算为 1.5 小时存储，分钟口径 90
        mockMvc.perform(post("/api/equipment/eq-xu/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"xu-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","unit":"MINUTES","cumulativeValue":90}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeValue").value(1.5))
                .andExpect(jsonPath("$.cumulativeMinutes").value(90));

        // 换算留痕：一条记录，只读稳定排序
        mockMvc.perform(get("/api/equipment/eq-xu/conversions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].sourceUnit").value("MINUTES"))
                .andExpect(jsonPath("$[0].sourceValue").value(90))
                .andExpect(jsonPath("$[0].convertedValue").value(1.5))
                .andExpect(jsonPath("$[0].convertedMinutes").value(90))
                .andExpect(jsonPath("$[0].requestId").value("xu-1"));

        // 同单位提交不产生留痕
        mockMvc.perform(post("/api/equipment/eq-xu/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"xu-2","expectedVersion":2,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","unit":"HOURS","cumulativeValue":2}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-xu/conversions"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 读数列表：存储口径为登记单位
        mockMvc.perform(get("/api/equipment/eq-xu/readings"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cumulativeValue").value(1.5))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(90))
                .andExpect(jsonPath("$[1].cumulativeValue").value(2))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(120));
    }

    @Test
    void reviseReading_crossUnit_recordsTraceWithNewRevision() throws Exception {
        registerHours("eq-xr", "10");
        postJson("/api/equipment/eq-xr/readings", """
                {"requestId":"xr-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeValue":1}
                """);

        // 以 MINUTES 修订为 150 分钟 → 2.5 小时
        mockMvc.perform(post("/api/equipment/eq-xr/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"xr-2","expectedVersion":2,"unit":"MINUTES",
                                 "cumulativeValue":150}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeValue").value(2.5))
                .andExpect(jsonPath("$.cumulativeMinutes").value(150))
                .andExpect(jsonPath("$.revisionNo").value(2));

        // 留痕记录关联修订号 2；修订历史保留登记单位值
        mockMvc.perform(get("/api/equipment/eq-xr/conversions"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].revisionNo").value(2))
                .andExpect(jsonPath("$[0].convertedValue").value(2.5));
        mockMvc.perform(get("/api/equipment/eq-xr/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cumulativeValue").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(60))
                .andExpect(jsonPath("$[1].cumulativeValue").value(2.5))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(150));
    }

    // ---------- 换算分钟口径单调性 ----------

    @Test
    void monotonic_conversionRoundingEqualityAllowed() throws Exception {
        registerHours("eq-eq", "100");
        // r1：1 小时 → 60 分钟
        postJson("/api/equipment/eq-eq/readings", """
                {"requestId":"eq-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeValue":1}
                """);
        // r2：跨单位提交 60 分钟 → 1.00 小时 → 60 分钟，与 r1 相等视为非递减，不 422
        mockMvc.perform(post("/api/equipment/eq-eq/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"eq-2","expectedVersion":2,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","unit":"MINUTES","cumulativeValue":60}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(60));
        // 留痕记录换算来源以便追溯
        mockMvc.perform(get("/api/equipment/eq-eq/conversions"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceUnit").value("MINUTES"))
                .andExpect(jsonPath("$[0].convertedMinutes").value(60));
    }

    @Test
    void monotonic_crossUnitViolation_returns422() throws Exception {
        registerHours("eq-vio", "100");
        postJson("/api/equipment/eq-vio/readings", """
                {"requestId":"vio-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeValue":2}
                """);
        // 跨单位提交 90 分钟 = 1.5 小时 < 前一条 2 小时 → 422，且不留痕
        mockMvc.perform(post("/api/equipment/eq-vio/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"vio-2","expectedVersion":2,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","unit":"MINUTES","cumulativeValue":90}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));
        mockMvc.perform(get("/api/equipment/eq-vio/conversions"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-vio/readings"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- 判定统一口径 ----------

    @Test
    void status_dueJudgmentUnifiedInMinutes() throws Exception {
        // HOURS 设备，周期 2 小时 = 120 分钟
        registerHours("eq-due", "2");
        postJson("/api/equipment/eq-due/readings", """
                {"requestId":"due-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeValue":1.5}
                """);
        mockMvc.perform(get("/api/equipment/eq-due/status"))
                .andExpect(jsonPath("$.measurementUnit").value("HOURS"))
                .andExpect(jsonPath("$.latestCumulativeValue").value(1.5))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(90))
                .andExpect(jsonPath("$.runMinutes").value(90))
                .andExpect(jsonPath("$.runValue").value(1.5))
                .andExpect(jsonPath("$.status").value("OK"));

        // 跨单位再提交 30 分钟 → 累计 2 小时 = 120 分钟，达到周期 → DUE
        postJson("/api/equipment/eq-due/readings", """
                {"requestId":"due-2","expectedVersion":2,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","unit":"MINUTES","cumulativeValue":120}
                """);
        mockMvc.perform(get("/api/equipment/eq-due/status"))
                .andExpect(jsonPath("$.runMinutes").value(120))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 完成保养（锚点 r2），运行分钟归零 → OK；与纯分钟设备语义一致
        mockMvc.perform(post("/api/equipment/eq-due/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"due-m1","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeValue").value(2))
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(120));
        mockMvc.perform(get("/api/equipment/eq-due/status"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeValue").value(2))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(120))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    // ---------- 展示单位切换 ----------

    @Test
    void status_displayUnitParam_convertsOnlyPresentation() throws Exception {
        registerHours("eq-disp", "2");
        postJson("/api/equipment/eq-disp/readings", """
                {"requestId":"disp-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeValue":1.5}
                """);

        // 默认展示单位 = 登记单位
        mockMvc.perform(get("/api/equipment/eq-disp/status"))
                .andExpect(jsonPath("$.displayUnit").value("HOURS"))
                .andExpect(jsonPath("$.displayRunValue").value(1.5))
                .andExpect(jsonPath("$.displayMaintenancePeriodValue").value(2));

        // unit=MINUTES：展示层换算，存储口径不变
        mockMvc.perform(get("/api/equipment/eq-disp/status").param("unit", "MINUTES"))
                .andExpect(jsonPath("$.displayUnit").value("MINUTES"))
                .andExpect(jsonPath("$.displayRunValue").value(90))
                .andExpect(jsonPath("$.displayMaintenancePeriodValue").value(120))
                .andExpect(jsonPath("$.runMinutes").value(90))
                .andExpect(jsonPath("$.runValue").value(1.5));

        // 非法展示单位 → 400
        mockMvc.perform(get("/api/equipment/eq-disp/status").param("unit", "DAYS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_UNIT"));
    }

    // ---------- 幂等与单位字段 ----------

    @Test
    void idempotency_unitFieldsParticipateInFingerprint() throws Exception {
        registerHours("eq-iup", "10");
        MvcResult first = postJson("/api/equipment/eq-iup/readings", """
                {"requestId":"iup-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","unit":"MINUTES","cumulativeValue":60}
                """);
        assert first.getResponse().getStatus() == 201;

        // 同键同参重放：返回首次结果，留痕不重复
        MvcResult replay = postJson("/api/equipment/eq-iup/readings", """
                {"requestId":"iup-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","unit":"MINUTES","cumulativeValue":60}
                """);
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-iup/conversions"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 同键异参（单位不同）→ 409
        mockMvc.perform(post("/api/equipment/eq-iup/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iup-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","unit":"HOURS","cumulativeValue":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：超小数位 400 后同 requestId 修正可成功
        mockMvc.perform(post("/api/equipment/eq-iup/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iup-2","expectedVersion":2,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeValue":1.555}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALUE_SCALE_EXCEEDED"));
        mockMvc.perform(post("/api/equipment/eq-iup/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iup-2","expectedVersion":2,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeValue":1.55}
                                """))
                .andExpect(status().isCreated());
    }

    // ---------- 单位不可更改 ----------

    @Test
    void measurementUnit_noUpdateEndpointAndLegacyMinutesCoexist() throws Exception {
        // MINUTES 设备用兼容字段提交；HOURS 设备用兼容字段提交（按分钟换算）
        registerHours("eq-coex", "5");
        mockMvc.perform(post("/api/equipment/eq-coex/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"coex-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":120}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeValue").value(2))
                .andExpect(jsonPath("$.cumulativeMinutes").value(120));
        // 兼容字段按分钟解释 → 跨单位留痕
        mockMvc.perform(get("/api/equipment/eq-coex/conversions"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceUnit").value("MINUTES"))
                .andExpect(jsonPath("$[0].convertedValue").value(2));
    }
}
