package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
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
 * 闭合应力循环账本测试（真实 H2 内存库，MySQL 兼容模式）：
 * 主流程（损伤达阈值 DUE、保养冻结历史）、失败分支（缺回落/越界/重叠/跨锚点/超 long）、幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FatigueCycleApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM fatigue_cycle");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void register(String equipmentId, long periodMinutes,
                          Long fatigueThreshold, Long baselineStressMpa) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("{\"requestId\":\"reg-").append(equipmentId)
                .append("\",\"equipmentId\":\"").append(equipmentId)
                .append("\",\"maintenancePeriodMinutes\":").append(periodMinutes);
        if (fatigueThreshold != null) {
            body.append(",\"fatigueThreshold\":").append(fatigueThreshold);
        }
        if (baselineStressMpa != null) {
            body.append(",\"baselineStressMpa\":").append(baselineStressMpa);
        }
        body.append("}");
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    private MvcResult addCycle(String equipmentId, String requestId, long expectedVersion,
                               String cycleId, String fromReadingId, int fromRevisionNo,
                               String toReadingId, int toRevisionNo, String peakAt, String returnAt,
                               long peakStress, long returnStress) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/cycles", """
                {"requestId":"%s","expectedVersion":%d,"cycleId":"%s",
                 "fromReadingId":"%s","fromRevisionNo":%d,"toReadingId":"%s","toRevisionNo":%d,
                 "peakAt":"%s","returnAt":"%s","peakStressMpa":%d,"returnStressMpa":%d,
                 "operator":"tester"}
                """.formatted(requestId, expectedVersion, cycleId, fromReadingId, fromRevisionNo,
                toReadingId, toRevisionNo, peakAt, returnAt, peakStress, returnStress));
    }

    private MvcResult completeMaintenance(String equipmentId, String requestId, long expectedVersion,
                                          String readingId, int anchorRevisionNo) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/maintenances", """
                {"requestId":"%s","expectedVersion":%d,"readingId":"%s","anchorRevisionNo":%d}
                """.formatted(requestId, expectedVersion, readingId, anchorRevisionNo));
    }

    // ---------- 主流程：基准 10、阈值 125，闭合峰 15 回 10 损伤 125 即 DUE；保养后历史保留 ----------

    @Test
    void mainFlow_fatigueDueAndMaintenanceRetainsHistory() throws Exception {
        register("eq-f1", 10000, 125L, 10L);
        addReading("eq-f1", "f1-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f1", "f1-r2", 2, "r2", "2026-01-01T12:00:00Z", 100);

        mockMvc.perform(get("/api/equipment/eq-f1/status"))
                .andExpect(jsonPath("$.fatigueThreshold").value(125))
                .andExpect(jsonPath("$.baselineStressMpa").value(10))
                .andExpect(jsonPath("$.currentRoundDamage").value(0))
                .andExpect(jsonPath("$.historicalDamage").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 闭合循环：峰 15 回 10，损伤 (15-10)^3 = 125，达到阈值 125 → DUE（工时远未到周期）
        mockMvc.perform(post("/api/equipment/eq-f1/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f1-c1","expectedVersion":3,"cycleId":"c1",
                                 "fromReadingId":"r1","fromRevisionNo":1,"toReadingId":"r2","toRevisionNo":1,
                                 "peakAt":"2026-01-01T11:00:00Z","returnAt":"2026-01-01T11:30:00Z",
                                 "peakStressMpa":15,"returnStressMpa":10,"operator":"tester"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cycleId").value("c1"))
                .andExpect(jsonPath("$.damage").value(125))
                .andExpect(jsonPath("$.operator").value("tester"))
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        mockMvc.perform(get("/api/equipment/eq-f1/status"))
                .andExpect(jsonPath("$.runMinutes").value(100))
                .andExpect(jsonPath("$.currentRoundDamage").value(125))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 完成保养：锚点 r2，循环冻结进历史轮
        MvcResult maintenance = completeMaintenance("eq-f1", "f1-m1", 4, "r2", 1);
        assertEquals(201, maintenance.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-f1/status"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.currentRoundDamage").value(0))
                .andExpect(jsonPath("$.historicalDamage").value(125))
                .andExpect(jsonPath("$.status").value("OK"));

        // 账本只增不改：历史 125 仍保留
        mockMvc.perform(get("/api/equipment/eq-f1/cycles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].cycleId").value("c1"))
                .andExpect(jsonPath("$[0].damage").value(125))
                .andExpect(jsonPath("$[0].fromReadingId").value("r1"))
                .andExpect(jsonPath("$[0].toReadingId").value("r2"));
    }

    // ---------- 缺回落 / 峰值不越基准：422 且不写损伤 ----------

    @Test
    void notClosedOrWeakPeak_rejectedWithoutDamage() throws Exception {
        register("eq-f2", 10000, 125L, 10L);
        addReading("eq-f2", "f2-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f2", "f2-r2", 2, "r2", "2026-01-01T12:00:00Z", 100);

        // 缺回落（回落值 12 不等于基准 10）→ 422，不写半循环
        MvcResult notClosed = addCycle("eq-f2", "f2-c1", 3, "c1", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 15, 12);
        assertEquals(422, notClosed.getResponse().getStatus());
        // 峰值等于基准 → 422
        MvcResult equalPeak = addCycle("eq-f2", "f2-c2", 3, "c2", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 10, 10);
        assertEquals(422, equalPeak.getResponse().getStatus());
        // 峰值低于基准 → 422
        MvcResult lowPeak = addCycle("eq-f2", "f2-c3", 3, "c3", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 5, 10);
        assertEquals(422, lowPeak.getResponse().getStatus());
        // 操作者必填
        MvcResult noOperator = postJson("/api/equipment/eq-f2/cycles", """
                {"requestId":"f2-c4","expectedVersion":3,"cycleId":"c4",
                 "fromReadingId":"r1","fromRevisionNo":1,"toReadingId":"r2","toRevisionNo":1,
                 "peakAt":"2026-01-01T11:00:00Z","returnAt":"2026-01-01T11:30:00Z",
                 "peakStressMpa":15,"returnStressMpa":10}
                """);
        assertEquals(400, noOperator.getResponse().getStatus());

        // 失败分支均不落库、不增加损伤、不推进版本
        mockMvc.perform(get("/api/equipment/eq-f2/cycles"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-f2/status"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.currentRoundDamage").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    // ---------- 时刻越界与区间重叠 ----------

    @Test
    void outOfBoundsAndOverlap_rejected() throws Exception {
        register("eq-f3", 10000, 125L, 10L);
        addReading("eq-f3", "f3-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f3", "f3-r2", 2, "r2", "2026-01-01T12:00:00Z", 100);

        // 升峰早于起始读数 → 422
        assertEquals(422, addCycle("eq-f3", "f3-c1", 3, "c1", "r1", 1, "r2", 1,
                "2026-01-01T09:30:00Z", "2026-01-01T11:00:00Z", 15, 10).getResponse().getStatus());
        // 回落晚于结束读数 → 422
        assertEquals(422, addCycle("eq-f3", "f3-c2", 3, "c2", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T12:30:00Z", 15, 10).getResponse().getStatus());
        // 时刻不递增 → 422
        assertEquals(422, addCycle("eq-f3", "f3-c3", 3, "c3", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:00:00Z", 15, 10).getResponse().getStatus());
        assertEquals(422, addCycle("eq-f3", "f3-c4", 3, "c4", "r1", 1, "r2", 1,
                "2026-01-01T11:30:00Z", "2026-01-01T11:00:00Z", 15, 10).getResponse().getStatus());

        // 合法循环
        assertEquals(201, addCycle("eq-f3", "f3-c5", 3, "c5", "r1", 1, "r2", 1,
                "2026-01-01T10:30:00Z", "2026-01-01T11:00:00Z", 15, 10).getResponse().getStatus());
        // 与已有循环相交 → 422
        assertEquals(422, addCycle("eq-f3", "f3-c6", 4, "c6", "r1", 1, "r2", 1,
                "2026-01-01T10:45:00Z", "2026-01-01T11:15:00Z", 15, 10).getResponse().getStatus());
        // 包含已有循环 → 422
        assertEquals(422, addCycle("eq-f3", "f3-c7", 4, "c7", "r1", 1, "r2", 1,
                "2026-01-01T10:00:00Z", "2026-01-01T12:00:00Z", 15, 10).getResponse().getStatus());
        // 端点相接不算重叠 → 201
        assertEquals(201, addCycle("eq-f3", "f3-c8", 4, "c8", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 16, 10).getResponse().getStatus());

        mockMvc.perform(get("/api/equipment/eq-f3/cycles"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cycleId").value("c5"))
                .andExpect(jsonPath("$[1].cycleId").value("c8"));
        mockMvc.perform(get("/api/equipment/eq-f3/status"))
                .andExpect(jsonPath("$.currentRoundDamage").value(125 + 216));
    }

    // ---------- 引用读数规则：修订号快照、引用后不可修订 ----------

    @Test
    void referencedReadingRules_revisionConflictAndNoRevise() throws Exception {
        register("eq-f4", 10000, 125L, 10L);
        addReading("eq-f4", "f4-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f4", "f4-r2", 2, "r2", "2026-01-01T12:00:00Z", 100);

        // 引用读数不存在 → 404
        assertEquals(404, addCycle("eq-f4", "f4-c1", 3, "c1", "r1", 1, "nope", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 15, 10).getResponse().getStatus());
        // 引用修订号与当前不一致 → 409
        MvcResult revisionConflict = addCycle("eq-f4", "f4-c2", 3, "c2", "r1", 2, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 15, 10);
        assertEquals(409, revisionConflict.getResponse().getStatus());

        // 合法引用 → 201
        assertEquals(201, addCycle("eq-f4", "f4-c3", 3, "c3", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 15, 10).getResponse().getStatus());

        // 已被循环引用的读数不可修订 → 409
        MvcResult reviseFrom = postJson("/api/equipment/eq-f4/readings/r1/revisions", """
                {"requestId":"f4-rv1","expectedVersion":4,"cumulativeMinutes":10}
                """);
        assertEquals(409, reviseFrom.getResponse().getStatus());
        MvcResult reviseTo = postJson("/api/equipment/eq-f4/readings/r2/revisions", """
                {"requestId":"f4-rv2","expectedVersion":4,"cumulativeMinutes":110}
                """);
        assertEquals(409, reviseTo.getResponse().getStatus());

        // 修订未生效：修订历史仍只有初始版本，版本未推进
        mockMvc.perform(get("/api/equipment/eq-f4/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-f4/status"))
                .andExpect(jsonPath("$.version").value(4));
    }

    // ---------- 跨锚点循环：保养时与写入时均拒绝 ----------

    @Test
    void spanningAnchor_rejectedAtMaintenanceAndAtWrite() throws Exception {
        register("eq-f5", 10000, 125L, 10L);
        addReading("eq-f5", "f5-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f5", "f5-r2", 2, "r2", "2026-01-01T11:00:00Z", 50);
        addReading("eq-f5", "f5-r3", 3, "r3", "2026-01-01T12:00:00Z", 100);

        // 循环区间 [10:30, 11:30] 跨过 r2（11:00）
        assertEquals(201, addCycle("eq-f5", "f5-c1", 4, "c1", "r1", 1, "r3", 1,
                "2026-01-01T10:30:00Z", "2026-01-01T11:30:00Z", 15, 10).getResponse().getStatus());

        // 以 r2 为锚点保养：存在跨锚点循环 → 422，不能把一个损伤拆进两轮
        MvcResult spanning = completeMaintenance("eq-f5", "f5-m1", 5, "r2", 1);
        assertEquals(422, spanning.getResponse().getStatus());

        // 以 r3 为锚点保养：循环完整落在锚点之前 → 201，损伤归入历史轮
        assertEquals(201, completeMaintenance("eq-f5", "f5-m2", 5, "r3", 1).getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-f5/status"))
                .andExpect(jsonPath("$.historicalDamage").value(125))
                .andExpect(jsonPath("$.currentRoundDamage").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 新循环不得跨越已有锚点（r3@12:00）→ 422
        addReading("eq-f5", "f5-r4", 6, "r4", "2026-01-01T13:00:00Z", 150);
        assertEquals(422, addCycle("eq-f5", "f5-c2", 7, "c2", "r2", 1, "r4", 1,
                "2026-01-01T11:45:00Z", "2026-01-01T12:15:00Z", 15, 10).getResponse().getStatus());

        // 完全落在锚点之后的循环 → 201，损伤计入本轮并再次 DUE
        assertEquals(201, addCycle("eq-f5", "f5-c3", 7, "c3", "r3", 1, "r4", 1,
                "2026-01-01T12:10:00Z", "2026-01-01T12:30:00Z", 15, 10).getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-f5/status"))
                .andExpect(jsonPath("$.currentRoundDamage").value(125))
                .andExpect(jsonPath("$.historicalDamage").value(125))
                .andExpect(jsonPath("$.status").value("DUE"));
    }

    // ---------- 损伤精确整数：超 long 拒绝 400 ----------

    @Test
    void damageOverflow_badRequest() throws Exception {
        register("eq-f6", 10000, Long.MAX_VALUE, 0L);
        addReading("eq-f6", "f6-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f6", "f6-r2", 2, "r2", "2026-01-01T12:00:00Z", 100);

        // (2^21)^3 = 2^63 超出 long → 400
        MvcResult overflow = addCycle("eq-f6", "f6-c1", 3, "c1", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 2097152, 0);
        assertEquals(400, overflow.getResponse().getStatus());

        // (2^21 - 1)^3 仍在 long 范围内 → 201，损伤为精确整数
        MvcResult maxOk = addCycle("eq-f6", "f6-c2", 3, "c2", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 2097151, 0);
        assertEquals(201, maxOk.getResponse().getStatus());
        long expected = 2097151L * 2097151L * 2097151L;
        mockMvc.perform(get("/api/equipment/eq-f6/cycles"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].damage").value(expected));
        mockMvc.perform(get("/api/equipment/eq-f6/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.currentRoundDamage").value(expected));
    }

    // ---------- 幂等：同参重放、异参 409、失败不占键 ----------

    @Test
    void idempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-f7", 10000, 125L, 10L);
        addReading("eq-f7", "f7-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f7", "f7-r2", 2, "r2", "2026-01-01T12:00:00Z", 100);

        String body = """
                {"requestId":"f7-c1","expectedVersion":3,"cycleId":"c1",
                 "fromReadingId":"r1","fromRevisionNo":1,"toReadingId":"r2","toRevisionNo":1,
                 "peakAt":"2026-01-01T11:00:00Z","returnAt":"2026-01-01T11:30:00Z",
                 "peakStressMpa":15,"returnStressMpa":10,"operator":"tester"}
                """;
        MvcResult first = postJson("/api/equipment/eq-f7/cycles", body);
        assertEquals(201, first.getResponse().getStatus());

        // 同键同参：重放首次完整响应，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-f7/cycles", body);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-f7/cycles"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-f7/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.currentRoundDamage").value(125));

        // 同键异参 → 409
        MvcResult mismatch = postJson("/api/equipment/eq-f7/cycles", """
                {"requestId":"f7-c1","expectedVersion":3,"cycleId":"c1",
                 "fromReadingId":"r1","fromRevisionNo":1,"toReadingId":"r2","toRevisionNo":1,
                 "peakAt":"2026-01-01T11:00:00Z","returnAt":"2026-01-01T11:30:00Z",
                 "peakStressMpa":16,"returnStressMpa":10,"operator":"tester"}
                """);
        assertEquals(409, mismatch.getResponse().getStatus());

        // 失败不占键：422 后同 requestId 修正参数可成功
        assertEquals(422, addCycle("eq-f7", "f7-c2", 4, "c2", "r1", 1, "r2", 1,
                "2026-01-01T11:40:00Z", "2026-01-01T11:50:00Z", 15, 12).getResponse().getStatus());
        assertEquals(201, addCycle("eq-f7", "f7-c2", 4, "c2", "r1", 1, "r2", 1,
                "2026-01-01T11:40:00Z", "2026-01-01T11:50:00Z", 15, 10).getResponse().getStatus());

        mockMvc.perform(get("/api/equipment/eq-f7/cycles"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-f7/status"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.currentRoundDamage").value(250));
    }

    // ---------- 未配置疲劳参数：循环拒绝，工时门禁不受影响 ----------

    @Test
    void fatigueNotConfigured_cycleRejectedAndHoursGateStillWorks() throws Exception {
        register("eq-f8", 100, null, null);
        addReading("eq-f8", "f8-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f8", "f8-r2", 2, "r2", "2026-01-01T12:00:00Z", 150);

        // 未配置基准应力：不能写入循环 → 422
        MvcResult cycle = addCycle("eq-f8", "f8-c1", 3, "c1", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 15, 10);
        assertEquals(422, cycle.getResponse().getStatus());

        // 原工时门禁不绕过：本轮运行 150 分钟达到周期 100 → DUE
        mockMvc.perform(get("/api/equipment/eq-f8/status"))
                .andExpect(jsonPath("$.runMinutes").value(150))
                .andExpect(jsonPath("$.currentRoundDamage").value(0))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 工时保养正常完成，保养后回到 OK
        assertEquals(201, completeMaintenance("eq-f8", "f8-m1", 3, "r2", 1)
                .getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-f8/status"))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    // ---------- 版本校验：循环写入核对 expectedVersion ----------

    @Test
    void versionConflictOnCycle_noSideEffects() throws Exception {
        register("eq-f9", 10000, 125L, 10L);
        addReading("eq-f9", "f9-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f9", "f9-r2", 2, "r2", "2026-01-01T12:00:00Z", 100);

        MvcResult conflict = addCycle("eq-f9", "f9-c1", 99, "c1", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 15, 10);
        assertEquals(409, conflict.getResponse().getStatus());

        // 版本冲突失败不占键：同 requestId 用正确版本可成功
        assertEquals(201, addCycle("eq-f9", "f9-c1", 3, "c1", "r1", 1, "r2", 1,
                "2026-01-01T11:00:00Z", "2026-01-01T11:30:00Z", 15, 10).getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-f9/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.currentRoundDamage").value(125));

        // cycleId 设备内唯一 → 409
        assertEquals(409, addCycle("eq-f9", "f9-c2", 4, "c1", "r1", 1, "r2", 1,
                "2026-01-01T11:40:00Z", "2026-01-01T11:50:00Z", 15, 10).getResponse().getStatus());
    }
}

