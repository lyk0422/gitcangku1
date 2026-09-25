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
 * 多计量单位换算与保养判定统一测试（真实 H2 内存库，MySQL 兼容模式）。
 * 覆盖：单位登记与校验、换算精度、跨单位单调性、判定统一口径、展示单位、换算留痕、幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentMultiUnitApiTests {

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

    private void registerHours(String equipmentId, String periodHours) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","unit":"HOURS",
                                 "maintenancePeriod":%s}
                                """.formatted(equipmentId, equipmentId, periodHours)))
                .andExpect(status().isCreated());
    }

    // ---------- 设备单位登记与配置查询 ----------

    @Test
    void register_hoursUnit_convertsPeriodToMinutes() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-h1","equipmentId":"eq-h1","unit":"HOURS",
                                 "maintenancePeriod":2.5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("HOURS"))
                .andExpect(jsonPath("$.maintenancePeriod").value(2.5))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(150))
                .andExpect(jsonPath("$.version").value(1));

        // 设备单位配置查询（只读）
        mockMvc.perform(get("/api/equipment/eq-h1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("HOURS"))
                .andExpect(jsonPath("$.maintenancePeriod").value(2.5))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(150));

        // 单位不可更改：同设备重复登记（即使换单位）一律冲突
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-h1b","equipmentId":"eq-h1","unit":"MINUTES",
                                 "maintenancePeriodMinutes":150}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_EXISTS"));

        // 缺省单位为 MINUTES，行为与既有实现一致
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-m1","equipmentId":"eq-m1","maintenancePeriodMinutes":100}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("MINUTES"))
                .andExpect(jsonPath("$.maintenancePeriod").value(100))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(100));
    }

    @Test
    void register_invalidUnitOrPeriod_badRequest() throws Exception {
        // 非法单位标签
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-b1","equipmentId":"eq-b1","unit":"DAYS",
                                 "maintenancePeriod":2.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNIT_INVALID"));
        // HOURS 设备缺少周期值
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-b2","equipmentId":"eq-b2","unit":"HOURS"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERIOD_REQUIRED"));
        // 周期超过 2 位小数
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-b3","equipmentId":"eq-b3","unit":"HOURS",
                                 "maintenancePeriod":2.555}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERIOD_INVALID"));
        // 周期为 0
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-b4","equipmentId":"eq-b4","unit":"HOURS",
                                 "maintenancePeriod":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERIOD_INVALID"));
        // MINUTES 设备缺少分钟周期
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-b5","equipmentId":"eq-b5"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PERIOD_REQUIRED"));
        // 失败不占键：修正参数后同 requestId 可成功
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-b5","equipmentId":"eq-b5","maintenancePeriodMinutes":60}
                                """))
                .andExpect(status().isCreated());
    }

    // ---------- HOURS 设备：读数、判定与保养统一口径 ----------

    @Test
    void hoursEquipment_judgmentUsesConvertedMinutes() throws Exception {
        registerHours("eq-hj", "2.5");

        // 初始状态：展示单位缺省为登记单位 HOURS
        mockMvc.perform(get("/api/equipment/eq-hj/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("HOURS"))
                .andExpect(jsonPath("$.maintenancePeriod").value(2.5))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(150))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.displayUnit").value("HOURS"))
                .andExpect(jsonPath("$.displayRunValue").value(0.0))
                .andExpect(jsonPath("$.displayPeriodValue").value(2.5));

        // 按登记单位提交小时读数：1.0h = 60 分钟
        postJson("/api/equipment/eq-hj/readings", """
                {"requestId":"hj-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeValue":1.0}
                """).getResponse().getStatus();
        mockMvc.perform(get("/api/equipment/eq-hj/status"))
                .andExpect(jsonPath("$.latestCumulativeValue").value(1.0))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(60))
                .andExpect(jsonPath("$.runMinutes").value(60))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.displayRunValue").value(1.0));

        // 达到周期：2.5h = 150 分钟 → DUE（与分钟口径完全一致）
        postJson("/api/equipment/eq-hj/readings", """
                {"requestId":"hj-r2","expectedVersion":2,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeValue":2.5}
                """);
        mockMvc.perform(get("/api/equipment/eq-hj/status"))
                .andExpect(jsonPath("$.runMinutes").value(150))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 完成保养后本轮运行分钟从锚点重算
        mockMvc.perform(post("/api/equipment/eq-hj/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hj-m1","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("HOURS"))
                .andExpect(jsonPath("$.anchorCumulativeValue").value(2.5))
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(150));
        mockMvc.perform(get("/api/equipment/eq-hj/status"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeValue").value(2.5))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(150))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 读数列表按登记单位展示原始值与换算分钟数
        mockMvc.perform(get("/api/equipment/eq-hj/readings"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].unit").value("HOURS"))
                .andExpect(jsonPath("$[0].cumulativeValue").value(1.0))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(60))
                .andExpect(jsonPath("$[1].cumulativeValue").value(2.5))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(150));

        // 同单位提交不产生换算留痕
        mockMvc.perform(get("/api/equipment/eq-hj/conversions"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- 跨单位提交：换算、留痕与单调性 ----------

    @Test
    void crossUnitSubmission_convertsAndRecordsTrace() throws Exception {
        // MINUTES 设备，周期 1000 分钟
        postJson("/api/equipment", """
                {"requestId":"reg-c1","equipmentId":"eq-c1","maintenancePeriodMinutes":1000}
                """);
        postJson("/api/equipment/eq-c1/readings", """
                {"requestId":"c1-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);

        // 附带 HOURS 标签提交：1.68h = 100.8 分钟 → 四舍五入 101，按设备单位（分钟）存储
        mockMvc.perform(post("/api/equipment/eq-c1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c1-r2","expectedVersion":2,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","unit":"HOURS","cumulativeValue":1.68}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("MINUTES"))
                .andExpect(jsonPath("$.cumulativeValue").value(101))
                .andExpect(jsonPath("$.cumulativeMinutes").value(101));

        // 换算留痕：只读、稳定排序，记录原始值与换算结果；不产生额外读数
        mockMvc.perform(get("/api/equipment/eq-c1/conversions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].readingId").value("r2"))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].submittedUnit").value("HOURS"))
                .andExpect(jsonPath("$[0].submittedValue").value(1.68))
                .andExpect(jsonPath("$[0].convertedValue").value(101))
                .andExpect(jsonPath("$[0].convertedMinutes").value(101))
                .andExpect(jsonPath("$[0].equalizedByRounding").value(false))
                .andExpect(jsonPath("$[0].requestId").value("c1-r2"));
        mockMvc.perform(get("/api/equipment/eq-c1/readings"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void crossUnitSubmission_roundingEqualityAcceptedNot422() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-e1","equipmentId":"eq-e1","maintenancePeriodMinutes":1000}
                """);
        postJson("/api/equipment/eq-e1/readings", """
                {"requestId":"e1-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);

        // 1.66h = 99.6 分钟 → 四舍五入 100，与相邻读数相等：视为非递减放行，不当作 422
        mockMvc.perform(post("/api/equipment/eq-e1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"e1-r2","expectedVersion":2,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","unit":"HOURS","cumulativeValue":1.66}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(100));

        // 留痕标记换算误差导致的相等，便于追溯
        mockMvc.perform(get("/api/equipment/eq-e1/conversions"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].equalizedByRounding").value(true))
                .andExpect(jsonPath("$[0].convertedMinutes").value(100));

        // 真实违反单调性（1.5h = 90 < 100）仍返回 422
        mockMvc.perform(post("/api/equipment/eq-e1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"e1-r3","expectedVersion":3,"readingId":"r3",
                                 "sampledAt":"2026-01-01T12:00:00Z","unit":"HOURS","cumulativeValue":1.5}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));
        // 422 不产生换算留痕
        mockMvc.perform(get("/api/equipment/eq-e1/conversions"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void crossUnitSubmission_toHoursEquipment_backfillMonotonic() throws Exception {
        registerHours("eq-hb", "10.0");
        // 按登记单位提交：1.0h（60 分钟）与 3.0h（180 分钟）
        postJson("/api/equipment/eq-hb/readings", """
                {"requestId":"hb-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeValue":1.0}
                """);
        postJson("/api/equipment/eq-hb/readings", """
                {"requestId":"hb-r3","expectedVersion":2,"readingId":"r3",
                 "sampledAt":"2026-01-01T12:00:00Z","cumulativeValue":3.0}
                """);

        // 跨单位补录：120 分钟 = 2.0h，落在 [60, 180] 内 → 接受，存储为登记单位小时值
        mockMvc.perform(post("/api/equipment/eq-hb/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hb-r2","expectedVersion":3,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","unit":"MINUTES","cumulativeValue":120}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unit").value("HOURS"))
                .andExpect(jsonPath("$.cumulativeValue").value(2.0))
                .andExpect(jsonPath("$.cumulativeMinutes").value(120));

        // 跨单位补录超过后邻：200 分钟 > 180 分钟 → 422
        mockMvc.perform(post("/api/equipment/eq-hb/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hb-r2x","expectedVersion":4,"readingId":"r2x",
                                 "sampledAt":"2026-01-01T11:30:00Z","unit":"MINUTES","cumulativeValue":200}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));

        // 留痕仅记录成功的换算
        mockMvc.perform(get("/api/equipment/eq-hb/conversions"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].submittedUnit").value("MINUTES"))
                .andExpect(jsonPath("$[0].submittedValue").value(120))
                .andExpect(jsonPath("$[0].convertedValue").value(2.0))
                .andExpect(jsonPath("$[0].convertedMinutes").value(120));
    }

    @Test
    void crossUnitSubmission_validationErrors() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-v1","equipmentId":"eq-v1","maintenancePeriodMinutes":1000}
                """);
        // 跨单位提交缺少 cumulativeValue
        mockMvc.perform(post("/api/equipment/eq-v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v1-r1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","unit":"HOURS"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALUE_REQUIRED"));
        // 提交值超过 2 位小数
        mockMvc.perform(post("/api/equipment/eq-v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v1-r2","expectedVersion":1,"readingId":"r2",
                                 "sampledAt":"2026-01-01T10:00:00Z","unit":"HOURS","cumulativeValue":1.234}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALUE_INVALID"));
        // 非法单位标签
        mockMvc.perform(post("/api/equipment/eq-v1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v1-r3","expectedVersion":1,"readingId":"r3",
                                 "sampledAt":"2026-01-01T10:00:00Z","unit":"DAYS","cumulativeValue":1.0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNIT_INVALID"));
        // HOURS 设备缺少 cumulativeValue
        registerHours("eq-v2", "5.0");
        mockMvc.perform(post("/api/equipment/eq-v2/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v2-r1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALUE_REQUIRED"));
        // 失败不占键：修正后同 requestId 成功
        mockMvc.perform(post("/api/equipment/eq-v2/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v2-r1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeValue":1.0}
                                """))
                .andExpect(status().isCreated());
    }

    // ---------- 修订的换算层 ----------

    @Test
    void revise_withUnitTag_convertsAndRecordsTrace() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-rv","equipmentId":"eq-rv","maintenancePeriodMinutes":1000}
                """);
        postJson("/api/equipment/eq-rv/readings", """
                {"requestId":"rv-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);

        // 附带单位标签修订：2.0h = 120 分钟
        mockMvc.perform(post("/api/equipment/eq-rv/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-rev1","expectedVersion":2,"unit":"HOURS","cumulativeValue":2.0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.cumulativeValue").value(120))
                .andExpect(jsonPath("$.cumulativeMinutes").value(120));

        // 修订历史保留两个版本；换算留痕关联修订号 2
        mockMvc.perform(get("/api/equipment/eq-rv/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(100))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(120));
        mockMvc.perform(get("/api/equipment/eq-rv/conversions"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].revisionNo").value(2))
                .andExpect(jsonPath("$[0].convertedMinutes").value(120));
    }

    // ---------- 展示单位参数 ----------

    @Test
    void status_displayUnitParam_convertsOnlyForDisplay() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-d1","equipmentId":"eq-d1","maintenancePeriodMinutes":100}
                """);
        postJson("/api/equipment/eq-d1/readings", """
                {"requestId":"d1-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":150}
                """);

        // 缺省展示单位 = 设备登记单位
        mockMvc.perform(get("/api/equipment/eq-d1/status"))
                .andExpect(jsonPath("$.displayUnit").value("MINUTES"))
                .andExpect(jsonPath("$.displayRunValue").value(150.0))
                .andExpect(jsonPath("$.displayPeriodValue").value(100.0))
                .andExpect(jsonPath("$.runMinutes").value(150))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 以 HOURS 展示：150 分钟 → 2.50，阈值 100 分钟 → 1.67；判定口径不变
        mockMvc.perform(get("/api/equipment/eq-d1/status").param("unit", "HOURS"))
                .andExpect(jsonPath("$.displayUnit").value("HOURS"))
                .andExpect(jsonPath("$.displayRunValue").value(2.5))
                .andExpect(jsonPath("$.displayPeriodValue").value(1.67))
                .andExpect(jsonPath("$.runMinutes").value(150))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(100))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 非法展示单位 → 400
        mockMvc.perform(get("/api/equipment/eq-d1/status").param("unit", "DAYS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNIT_INVALID"));
    }

    // ---------- 幂等与换算留痕 ----------

    @Test
    void idempotency_crossUnitReplayAndMismatch() throws Exception {
        postJson("/api/equipment", """
                {"requestId":"reg-i1","equipmentId":"eq-i1","maintenancePeriodMinutes":1000}
                """);

        MvcResult first = postJson("/api/equipment/eq-i1/readings", """
                {"requestId":"i1-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","unit":"HOURS","cumulativeValue":1.68}
                """);
        assert first.getResponse().getStatus() == 201;

        // 同键同参重放：返回首次结果，换算留痕不重复
        MvcResult replay = postJson("/api/equipment/eq-i1/readings", """
                {"requestId":"i1-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","unit":"HOURS","cumulativeValue":1.68}
                """);
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-i1/readings"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-i1/conversions"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 同键异参（不同提交值）→ 409
        mockMvc.perform(post("/api/equipment/eq-i1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"i1-r1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","unit":"HOURS","cumulativeValue":1.7}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void conversions_unknownEquipment_notFound() throws Exception {
        mockMvc.perform(get("/api/equipment/nope/conversions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
    }
}
