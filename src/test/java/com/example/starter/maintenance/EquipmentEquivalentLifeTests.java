package com.example.starter.maintenance;

import static org.hamcrest.Matchers.nullValue;
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

/**
 * 负荷加权等效寿命主流程测试（真实 H2）：加权累计、题干合成算例、到期提示、
 * 保养冻结基线、负荷档位更正重算、未启用设备的空口径。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentEquivalentLifeTests {

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

    private void register(String equipmentId, long periodMinutes, Long equivalentPeriodMinutes)
            throws Exception {
        String equivalent = equivalentPeriodMinutes == null ? "null" : equivalentPeriodMinutes.toString();
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d,
                                 "equivalentPeriodMinutes":%s}
                                """.formatted(equipmentId, equipmentId, periodMinutes, equivalent)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
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

    /** 题干合成算例：100 分钟 NORMAL 后 150 分钟 HEAVY 等效累计 200；第一读数改 80 后等效 80+140=220。 */
    @Test
    void weightedAccumulation_andCorrectionRecomputesBothIntervals() throws Exception {
        register("eq-eq", 10000, 250L);

        addReading("eq-eq", "eq-1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        addReading("eq-eq", "eq-2", 2, "r2", "2026-01-01T11:00:00Z", 150, "HEAVY", 201);

        // 100*1 + (150-100)*2 = 200
        mockMvc.perform(get("/api/equipment/eq-eq/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(150))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.equivalentRunMinutes").value(200))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(50))
                .andExpect(jsonPath("$.equivalentStatus").value("OK"))
                .andExpect(jsonPath("$.runMinutes").value(150))
                .andExpect(jsonPath("$.remainingMinutes").value(9850))
                .andExpect(jsonPath("$.status").value("OK"));
        mockMvc.perform(get("/api/equipment/eq-eq/readings"))
                .andExpect(jsonPath("$[0].loadWeight").value("NORMAL"))
                .andExpect(jsonPath("$[0].equivalentCumulativeMinutes").value(100))
                .andExpect(jsonPath("$[1].loadWeight").value("HEAVY"))
                .andExpect(jsonPath("$[1].equivalentCumulativeMinutes").value(200));

        // 更正第一读数 100 → 80：重算本间隔（80*1）与后一条间隔（(150-80)*2=140），等效 220
        mockMvc.perform(post("/api/equipment/eq-eq/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"eq-3","expectedVersion":3,"cumulativeMinutes":80}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.equivalentCumulativeMinutes").value(80));

        mockMvc.perform(get("/api/equipment/eq-eq/status"))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(220))
                .andExpect(jsonPath("$.equivalentRunMinutes").value(220))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(30));
        mockMvc.perform(get("/api/equipment/eq-eq/readings"))
                .andExpect(jsonPath("$[0].equivalentCumulativeMinutes").value(80))
                .andExpect(jsonPath("$[1].equivalentCumulativeMinutes").value(220));
        mockMvc.perform(get("/api/equipment/eq-eq/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(100))
                .andExpect(jsonPath("$[0].loadWeight").value("NORMAL"))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(80));
    }

    /** 等效剩余 ≤ 0 提示到期，但物理分钟规则保留且不受影响。 */
    @Test
    void equivalentDue_promptsWithoutRemovingPhysicalRule() throws Exception {
        register("eq-due", 10000, 200L);
        addReading("eq-due", "d-1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        addReading("eq-due", "d-2", 2, "r2", "2026-01-01T11:00:00Z", 150, "HEAVY", 201);

        // 等效 200、剩余 0 → DUE；物理 150 < 10000 → OK
        mockMvc.perform(get("/api/equipment/eq-due/status"))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(0))
                .andExpect(jsonPath("$.equivalentStatus").value("DUE"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.remainingMinutes").value(9850));

        // 继续运行使等效剩余为负，仍然 DUE
        addReading("eq-due", "d-3", 3, "r3", "2026-01-01T12:00:00Z", 160, "HEAVY", 201);
        mockMvc.perform(get("/api/equipment/eq-due/status"))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(220))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(-20))
                .andExpect(jsonPath("$.equivalentStatus").value("DUE"))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    /** 未启用等效寿命的设备：等效口径字段为 null，物理口径不变。 */
    @Test
    void equivalentDisabled_fieldsAreNull() throws Exception {
        register("eq-plain", 100, null);
        addReading("eq-plain", "p-1", 1, "r1", "2026-01-01T10:00:00Z", 150, "HEAVY", 201);
        mockMvc.perform(get("/api/equipment/eq-plain/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.remainingMinutes").value(-50))
                .andExpect(jsonPath("$.equivalentPeriodMinutes").value(nullValue()))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(nullValue()))
                .andExpect(jsonPath("$.equivalentRunMinutes").value(nullValue()))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(nullValue()))
                .andExpect(jsonPath("$.equivalentStatus").value(nullValue()));
    }

    /** 保养完成冻结最新读数与等效累计；后续新增读数与更正均不改变冻结基线。 */
    @Test
    void maintenance_freezesEquivalentBaseline() throws Exception {
        register("eq-frz", 10000, 1000L);
        addReading("eq-frz", "f-1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        addReading("eq-frz", "f-2", 2, "r2", "2026-01-01T11:00:00Z", 150, "HEAVY", 201);

        mockMvc.perform(post("/api/equipment/eq-frz/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f-3","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.latestReadingId").value("r2"))
                .andExpect(jsonPath("$.anchorEquivalentCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(150));

        mockMvc.perform(get("/api/equipment/eq-frz/status"))
                .andExpect(jsonPath("$.equivalentRunMinutes").value(0))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(1000))
                .andExpect(jsonPath("$.runMinutes").value(0));

        // 新读数推进当前等效，但不改变冻结的历史快照
        addReading("eq-frz", "f-4", 4, "r3", "2026-01-01T12:00:00Z", 260, "NORMAL", 201);
        mockMvc.perform(get("/api/equipment/eq-frz/status"))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(310))
                .andExpect(jsonPath("$.equivalentRunMinutes").value(110))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(890));

        // 更正保养前的历史读数：当前等效重算，冻结基线不变
        mockMvc.perform(post("/api/equipment/eq-frz/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f-5","expectedVersion":5,"cumulativeMinutes":80}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-frz/status"))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(330))
                .andExpect(jsonPath("$.equivalentRunMinutes").value(130));
        mockMvc.perform(get("/api/equipment/eq-frz/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].latestReadingId").value("r2"))
                .andExpect(jsonPath("$[0].anchorEquivalentCumulativeMinutes").value(200));

        Long frozen = jdbc.queryForObject(
                "SELECT anchor_equivalent_cumulative_minutes FROM maintenance"
                        + " WHERE equipment_id = 'eq-frz'", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(200L, frozen, "保养冻结的等效累计不得被后续写改变");
    }

    /** 负荷档位更正：只改权重也重算等效累计，修订历史记录档位变化。 */
    @Test
    void reviseLoadWeight_recomputesEquivalent() throws Exception {
        register("eq-w", 10000, 1000L);
        addReading("eq-w", "w-1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        addReading("eq-w", "w-2", 2, "r2", "2026-01-01T11:00:00Z", 150, "NORMAL", 201);
        mockMvc.perform(get("/api/equipment/eq-w/status"))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(150));

        // r2 档位 NORMAL → HEAVY：100*1 + 50*2 = 200
        mockMvc.perform(post("/api/equipment/eq-w/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-3","expectedVersion":3,"cumulativeMinutes":150,
                                 "loadWeight":"HEAVY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loadWeight").value("HEAVY"))
                .andExpect(jsonPath("$.equivalentCumulativeMinutes").value(200));
        mockMvc.perform(get("/api/equipment/eq-w/readings/r2/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].loadWeight").value("NORMAL"))
                .andExpect(jsonPath("$[1].loadWeight").value("HEAVY"));
        mockMvc.perform(get("/api/equipment/eq-w/status"))
                .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(200));
    }
}
