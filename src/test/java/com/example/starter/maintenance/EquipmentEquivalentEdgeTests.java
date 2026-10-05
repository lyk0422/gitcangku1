package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 等效寿命场景的失败分支与幂等边界：参数校验、失败不推进版本/不写修订、
 * 锚定读数不可更正、结构化指纹防分隔符碰撞、重放与异参 409、失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentEquivalentEdgeTests {

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

    private void register(String equipmentId, long periodMinutes, Long equivalentPeriodMinutes)
            throws Exception {
        String equivalent = equivalentPeriodMinutes == null ? "null" : equivalentPeriodMinutes.toString();
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d,
                                 "equivalentPeriodMinutes":%s}
                                """.formatted(equipmentId, equipmentId, periodMinutes, equivalent)))
                .andExpect(status().isCreated());
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            String loadWeight, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d,"loadWeight":"%s"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes, loadWeight)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------- 参数校验 ----------

    @Test
    void validation_loadWeightAndEquivalentPeriod() throws Exception {
        register("eq-val", 1000, null);

        // 新增读数缺负荷档位 → 400
        mockMvc.perform(post("/api/equipment/eq-val/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 非法档位 → 400
        addReading("eq-val", "v-2", 1, "r1", "2026-01-01T10:00:00Z", 100, "LIGHT", 400);
        addReading("eq-val", "v-3", 1, "r1", "2026-01-01T10:00:00Z", 100, "normal", 400);
        // 修订携带非法档位 → 400
        addReading("eq-val", "v-4", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        mockMvc.perform(post("/api/equipment/eq-val/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-5","expectedVersion":2,"cumulativeMinutes":120,
                                 "loadWeight":"TURBO"}
                                """))
                .andExpect(status().isBadRequest());

        // 等效周期必须为正整数
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-6","equipmentId":"eq-bad-eq","maintenancePeriodMinutes":100,
                                 "equivalentPeriodMinutes":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-7","equipmentId":"eq-bad-eq2","maintenancePeriodMinutes":100,
                                 "equivalentPeriodMinutes":-5}
                                """))
                .andExpect(status().isBadRequest());
        // 校验失败未落库
        mockMvc.perform(get("/api/equipment/eq-bad-eq/status"))
                .andExpect(status().isNotFound());
    }

    // ---------- 失败不推进版本、不写修订 ----------

    @Test
    void failures_doNotAdvanceVersionOrWriteRevisions() throws Exception {
        register("eq-fail", 10000, 1000L);
        addReading("eq-fail", "fl-1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        addReading("eq-fail", "fl-2", 2, "r2", "2026-01-01T11:00:00Z", 150, "HEAVY", 201);

        // 版本冲突 → 409，版本与数据不变
        mockMvc.perform(post("/api/equipment/eq-fail/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fl-3","expectedVersion":99,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 单调性违反（超过后一条 150）→ 422，版本与数据不变
        mockMvc.perform(post("/api/equipment/eq-fail/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fl-4","expectedVersion":3,"cumulativeMinutes":200}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));

        mockMvc.perform(get("/api/equipment/eq-fail/status"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(200));
        mockMvc.perform(get("/api/equipment/eq-fail/readings/r1/revisions"))
                .andExpect(jsonPath("$.length()").value(1));
        Long cumulative = jdbc.queryForObject(
                "SELECT cumulative_minutes FROM reading WHERE equipment_id = 'eq-fail' AND reading_id = 'r1'",
                Long.class);
        assertEquals(100L, cumulative, "失败的更正不得改变读数当前值");

        // 锚定读数不可更正 → 409
        mockMvc.perform(post("/api/equipment/eq-fail/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fl-5","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-fail/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fl-6","expectedVersion":4,"cumulativeMinutes":140}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));
        mockMvc.perform(get("/api/equipment/eq-fail/status"))
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(get("/api/equipment/eq-fail/readings/r2/revisions"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ---------- 幂等：结构化指纹、重放、失败不占键 ----------

    @Test
    void idempotency_structuredFingerprintAndReplay() throws Exception {
        // 参数含分隔符与引号：结构化指纹不碰撞，同键同参可重放
        MvcResult first = postJson("/api/equipment", """
                {"requestId":"sp-1","equipmentId":"eq|pipe\\"quote","maintenancePeriodMinutes":100,
                 "equivalentPeriodMinutes":50}
                """);
        assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = postJson("/api/equipment", """
                {"requestId":"sp-1","equipmentId":"eq|pipe\\"quote","maintenancePeriodMinutes":100,
                 "equivalentPeriodMinutes":50}
                """);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        // 同键异参（仅等效周期不同）→ 409，说明指纹区分了全部参数
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"sp-1","equipmentId":"eq|pipe\\"quote","maintenancePeriodMinutes":100,
                                 "equivalentPeriodMinutes":51}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        Integer equipmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM equipment WHERE equipment_id = 'eq|pipe\"quote'", Integer.class);
        assertEquals(1, equipmentCount, "特殊字符设备应只登记一次（重放不重复生效）");

        // 读数重放：响应体（含等效字段）与首次完全一致
        register("eq-rp", 10000, 1000L);
        MvcResult addFirst = postJson("/api/equipment/eq-rp/readings", """
                {"requestId":"rp-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"loadWeight":"HEAVY"}
                """);
        assertEquals(201, addFirst.getResponse().getStatus());
        MvcResult addReplay = postJson("/api/equipment/eq-rp/readings", """
                {"requestId":"rp-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"loadWeight":"HEAVY"}
                """);
        assertEquals(addFirst.getResponse().getContentAsString(),
                addReplay.getResponse().getContentAsString());
        // 同键仅档位不同 → 409
        mockMvc.perform(post("/api/equipment/eq-rp/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rp-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"loadWeight":"NORMAL"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mockMvc.perform(get("/api/equipment/eq-rp/readings"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].loadWeight").value("HEAVY"));

        // 失败不占键：422 后同 requestId 修正参数可成功
        addReading("eq-rp", "rp-2", 2, "r2", "2026-01-01T11:00:00Z", 50, "NORMAL", 422);
        addReading("eq-rp", "rp-2", 2, "r2", "2026-01-01T11:00:00Z", 150, "NORMAL", 201);
        mockMvc.perform(get("/api/equipment/eq-rp/status"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(250));
    }
}
