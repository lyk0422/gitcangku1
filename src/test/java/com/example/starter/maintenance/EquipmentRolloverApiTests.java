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
 * 量程回绕特性测试：量程登记、原始读数（raw + wrapCount）归一与校验、
 * 原累计接口在已启用量程设备上的拒绝（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentRolloverApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void register(String equipmentId, long periodMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.rangeM").doesNotExist());
    }

    private MvcResult registerRange(String equipmentId, String requestId, long expectedVersion,
                                    long rangeM, int expectedStatus) throws Exception {
        MvcResult result = postJson("/api/equipment/" + equipmentId + "/range", """
                {"requestId":"%s","expectedVersion":%d,"rangeM":%d}
                """.formatted(requestId, expectedVersion, rangeM));
        org.junit.jupiter.api.Assertions.assertEquals(expectedStatus, result.getResponse().getStatus());
        return result;
    }

    private MvcResult addRaw(String equipmentId, String requestId, long expectedVersion,
                             String readingId, String sampledAt, long raw, long wrapCount,
                             int expectedStatus) throws Exception {
        MvcResult result = postJson("/api/equipment/" + equipmentId + "/raw-readings", """
                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                 "sampledAt":"%s","raw":%d,"wrapCount":%d}
                """.formatted(requestId, expectedVersion, readingId, sampledAt, raw, wrapCount));
        org.junit.jupiter.api.Assertions.assertEquals(expectedStatus, result.getResponse().getStatus());
        return result;
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------- 主流程：量程登记 → 原始读数归一 → 状态按归一值判定 ----------

    @Test
    void rollover_mainFlow_normalizedCumulativeAndStatus() throws Exception {
        register("eq-roll", 500);
        registerRange("eq-roll", "range-1", 1, 1000, 201);

        // 量程登记后设备版本推进，响应携带量程
        mockMvc.perform(get("/api/equipment/eq-roll/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 回绕前：raw 990 / wrap 0 → 归一 990
        addRaw("eq-roll", "raw-1", 2, "r1", "2026-01-01T10:00:00Z", 990, 0, 201);
        // 回绕后：raw 5 / wrap 1 → 归一 1*1000+5 = 1005（回绕必须显式给出，不靠数值下降猜测）
        MvcResult r2 = addRaw("eq-roll", "raw-2", 3, "r2", "2026-01-01T11:00:00Z", 5, 1, 201);
        org.junit.jupiter.api.Assertions.assertTrue(
                r2.getResponse().getContentAsString().contains("\"cumulativeMinutes\":1005"));

        // 状态按归一最新值计算：1005 >= 周期 500 → DUE
        mockMvc.perform(get("/api/equipment/eq-roll/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(1005))
                .andExpect(jsonPath("$.runMinutes").value(1005))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 读数列表携带原始证据
        mockMvc.perform(get("/api/equipment/eq-roll/readings"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].raw").value(990))
                .andExpect(jsonPath("$[0].wrapCount").value(0))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(990))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].raw").value(5))
                .andExpect(jsonPath("$[1].wrapCount").value(1))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(1005));

        // 修订历史保留归一值与原始证据
        mockMvc.perform(get("/api/equipment/eq-roll/readings/r2/revisions"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(1005))
                .andExpect(jsonPath("$[0].raw").value(5))
                .andExpect(jsonPath("$[0].wrapCount").value(1));
    }

    // ---------- 量程登记规则 ----------

    @Test
    void registerRange_rules() throws Exception {
        register("eq-range", 100);
        // 版本冲突 → 409
        registerRange("eq-range", "rg-0", 9, 1000, 409);
        // 非正量程 → 400
        mockMvc.perform(post("/api/equipment/eq-range/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rg-bad","expectedVersion":1,"rangeM":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 正常登记
        registerRange("eq-range", "rg-1", 1, 1000, 201);
        // 重复登记 → 409（量程不可变）
        mockMvc.perform(post("/api/equipment/eq-range/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rg-2","expectedVersion":2,"rangeM":2000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RANGE_ALREADY_SET"));

        // 已有读数的设备不可再登记量程 → 409
        register("eq-has-readings", 100);
        addReading("eq-has-readings", "hr-1", 1, "r1", "2026-01-01T10:00:00Z", 10, 201);
        mockMvc.perform(post("/api/equipment/eq-has-readings/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rg-3","expectedVersion":2,"rangeM":1000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RANGE_HAS_READINGS"));
    }

    // ---------- 原始读数参数校验：非法或溢出 400 ----------

    @Test
    void addRaw_invalidOrOverflow_badRequest() throws Exception {
        register("eq-valid", 10000);
        registerRange("eq-valid", "vr-1", 1, 1000, 201);

        // raw == M → 400（须满足 0 <= raw < M）
        mockMvc.perform(post("/api/equipment/eq-valid/raw-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"vb-1","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","raw":1000,"wrapCount":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_OUT_OF_RANGE"));
        // raw 为负 → 400（参数校验）
        mockMvc.perform(post("/api/equipment/eq-valid/raw-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"vb-2","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","raw":-1,"wrapCount":0}
                                """))
                .andExpect(status().isBadRequest());
        // wrapCount 为负 → 400（参数校验）
        mockMvc.perform(post("/api/equipment/eq-valid/raw-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"vb-3","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","raw":1,"wrapCount":-1}
                                """))
                .andExpect(status().isBadRequest());
        // wrapCount * M + raw 溢出 long → 400
        mockMvc.perform(post("/api/equipment/eq-valid/raw-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"vb-4","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","raw":0,"wrapCount":9223372036854775807}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NORMALIZED_OVERFLOW"));

        // 失败均不占版本、不落库；合法写仍可用原版本前进
        addRaw("eq-valid", "vb-5", 2, "r1", "2026-01-01T10:00:00Z", 999, 0, 201);
        mockMvc.perform(get("/api/equipment/eq-valid/readings"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- 接口守卫：量程设备拒绝原累计接口，未启用设备拒绝原始接口 ----------

    @Test
    void endpointGuards_rangeAndLegacy() throws Exception {
        register("eq-guard", 10000);
        registerRange("eq-guard", "gd-1", 1, 1000, 201);
        addRaw("eq-guard", "gd-2", 2, "r1", "2026-01-01T10:00:00Z", 100, 0, 201);

        // 已启用量程：原累计新增接口 → 422
        mockMvc.perform(post("/api/equipment/eq-guard/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"gd-3","expectedVersion":3,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":200}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGE_ENABLED_REQUIRES_RAW"));
        // 已启用量程：原累计修订接口 → 422
        mockMvc.perform(post("/api/equipment/eq-guard/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"gd-4","expectedVersion":3,"cumulativeMinutes":150}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGE_ENABLED_REQUIRES_RAW"));

        // 未启用量程：原始读数新增 → 422
        register("eq-no-range", 10000);
        addReading("eq-no-range", "nr-1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        mockMvc.perform(post("/api/equipment/eq-no-range/raw-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nr-2","expectedVersion":2,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","raw":5,"wrapCount":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGE_NOT_ENABLED"));
        // 未启用量程：原始读数修订 → 422
        mockMvc.perform(post("/api/equipment/eq-no-range/readings/r1/raw-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nr-3","expectedVersion":2,"readingRevision":1,
                                 "raw":50,"wrapCount":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGE_NOT_ENABLED"));
    }
}
