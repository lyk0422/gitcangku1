package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 闭合应力循环账本测试（真实 H2 内存库，MySQL 兼容模式）。
 * 覆盖：主流程（基准 10、阈值 125、闭合峰 15 回 10 损伤 125 即 DUE）、失败分支、
 * 区间重叠与跨锚点、轮次归属、幂等；时钟使用可控固定时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentFatigueCycleTests {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM stress_cycle");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void register(String equipmentId, long periodMinutes, long fatigueThreshold,
                          long baselineStressMpa) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d,
                                 "fatigueThreshold":%d,"baselineStressMpa":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes,
                                fatigueThreshold, baselineStressMpa)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.fatigueThreshold").value((int) fatigueThreshold))
                .andExpect(jsonPath("$.baselineStressMpa").value((int) baselineStressMpa));
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

    private String cycleBody(String requestId, String operator, long expectedVersion, String cycleId,
                             long riseStress, String riseAt, String riseReading, int riseRev,
                             long fallStress, String fallAt, String fallReading, int fallRev) {
        return """
                {"requestId":"%s","operator":"%s","expectedVersion":%d,"cycleId":"%s",
                 "rise":{"stressMpa":%d,"sampledAt":"%s","readingId":"%s","readingRevisionNo":%d},
                 "fall":{"stressMpa":%d,"sampledAt":"%s","readingId":"%s","readingRevisionNo":%d}}
                """.formatted(requestId, operator, expectedVersion, cycleId,
                riseStress, riseAt, riseReading, riseRev,
                fallStress, fallAt, fallReading, fallRev);
    }

    private MvcResult addCycle(String equipmentId, String requestId, long expectedVersion,
                               String cycleId, long riseStress, String riseAt, String riseReading,
                               int riseRev, long fallStress, String fallAt, String fallReading,
                               int fallRev, int expectedStatus) throws Exception {
        MvcResult result = postJson("/api/equipment/" + equipmentId + "/cycles",
                cycleBody(requestId, "op-1", expectedVersion, cycleId,
                        riseStress, riseAt, riseReading, riseRev,
                        fallStress, fallAt, fallReading, fallRev));
        org.junit.jupiter.api.Assertions.assertEquals(expectedStatus, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return result;
    }

    // ---------- 主流程：闭合峰 15 回 10 损伤 125 即 DUE；保养后历史 125 仍保留 ----------

    @Test
    void mainFlow_closedCycleDueAndMaintenanceKeepsHistory() throws Exception {
        register("eq-f1", 100000, 125, 10);
        addReading("eq-f1", "f1-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f1", "f1-r2", 2, "r2", "2026-01-01T12:00:00Z", 60);

        mockMvc.perform(get("/api/equipment/eq-f1/status"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.currentRoundDamage").value(0));

        // 闭合循环：峰 15 > 基准 10，回落 10 = 基准，损伤 (15-10)^3 = 125
        MvcResult created = addCycle("eq-f1", "f1-c1", 3, "c1", 15, "2026-01-01T10:30:00Z", "r1", 1,
                10, "2026-01-01T11:00:00Z", "r2", 1, 201);
        org.junit.jupiter.api.Assertions.assertTrue(
                created.getResponse().getContentAsString().contains("\"damage\":125"));
        mockMvc.perform(get("/api/equipment/eq-f1/cycles"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].cycleId").value("c1"))
                .andExpect(jsonPath("$[0].operator").value("op-1"))
                .andExpect(jsonPath("$[0].damage").value(125))
                .andExpect(jsonPath("$[0].currentRound").value(true))
                .andExpect(jsonPath("$[0].equipmentVersion").value(4));

        // 损伤 125 达到阈值 125 → DUE（工时远未到周期）
        mockMvc.perform(get("/api/equipment/eq-f1/status"))
                .andExpect(jsonPath("$.runMinutes").value(60))
                .andExpect(jsonPath("$.currentRoundDamage").value(125))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 完成保养（可控时钟：completedAt 为固定时刻）
        mockMvc.perform(post("/api/equipment/eq-f1/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f1-m1","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completedAt").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        // 保养后当前轮损伤清零、状态回 OK
        mockMvc.perform(get("/api/equipment/eq-f1/status"))
                .andExpect(jsonPath("$.currentRoundDamage").value(0))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 历史 125 仍保留：循环记录保留且归历史轮
        mockMvc.perform(get("/api/equipment/eq-f1/cycles"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].damage").value(125))
                .andExpect(jsonPath("$[0].currentRound").value(false));

        // 同键同参重放：返回首次完整响应，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-f1/cycles",
                cycleBody("f1-c1", "op-1", 3, "c1", 15, "2026-01-01T10:30:00Z", "r1", 1,
                        10, "2026-01-01T11:00:00Z", "r2", 1));
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                replay.getResponse().getContentAsString().contains("\"damage\":125"));
        mockMvc.perform(get("/api/equipment/eq-f1/cycles"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-f1/status"))
                .andExpect(jsonPath("$.version").value(5));
    }

    // ---------- 失败分支：缺回落/峰值越界/时刻越界/溢出/引用错误，均不落库 ----------

    @Test
    void cycleValidationFailures_nothingWritten() throws Exception {
        register("eq-f2", 100000, 125, 10);
        addReading("eq-f2", "f2-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f2", "f2-r2", 2, "r2", "2026-01-01T12:00:00Z", 60);

        // 缺回落（无 fall 字段）→ 422，不写半循环
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f2-b1","operator":"op-1","expectedVersion":3,"cycleId":"b1",
                                 "rise":{"stressMpa":15,"sampledAt":"2026-01-01T10:30:00Z","readingId":"r1",
                                 "readingRevisionNo":1}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_NOT_CLOSED"));

        // 缺回 10：回落应力 12 ≠ 基准 10 → 提交失败、不增加损伤
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b3", "op-1", 3, "b3", 15, "2026-01-01T10:30:00Z",
                                "r1", 1, 12, "2026-01-01T11:00:00Z", "r2", 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_NOT_CLOSED"));

        // 峰值不大于基准 → 422
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b4", "op-1", 3, "b4", 10, "2026-01-01T10:30:00Z",
                                "r1", 1, 10, "2026-01-01T11:00:00Z", "r2", 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_PEAK_NOT_ABOVE_BASELINE"));

        // 损伤超 long（(3000000)^3 溢出）→ 400
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b5", "op-1", 3, "b5", 3000010, "2026-01-01T10:30:00Z",
                                "r1", 1, 10, "2026-01-01T11:00:00Z", "r2", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DAMAGE_OVERFLOW"));

        // 时刻越界：升峰早于引用读数 / 回落晚于引用读数 / 时刻不递增 → 422
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b6", "op-1", 3, "b6", 15, "2026-01-01T09:30:00Z",
                                "r1", 1, 10, "2026-01-01T11:00:00Z", "r2", 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_TIME_OUT_OF_BOUNDS"));
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b7", "op-1", 3, "b7", 15, "2026-01-01T10:30:00Z",
                                "r1", 1, 10, "2026-01-01T12:30:00Z", "r2", 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_TIME_OUT_OF_BOUNDS"));
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b8", "op-1", 3, "b8", 15, "2026-01-01T11:00:00Z",
                                "r1", 1, 10, "2026-01-01T10:30:00Z", "r2", 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_TIME_OUT_OF_BOUNDS"));

        // 引用读数不存在 → 404；引用修订号非现存 → 409
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b9", "op-1", 3, "b9", 15, "2026-01-01T10:30:00Z",
                                "nope", 1, 10, "2026-01-01T11:00:00Z", "r2", 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READING_NOT_FOUND"));
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b10", "op-1", 3, "b10", 15, "2026-01-01T10:30:00Z",
                                "r1", 2, 10, "2026-01-01T11:00:00Z", "r2", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_REVISION_CONFLICT"));

        // 版本冲突 → 409；操作者为空 → 400
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b11", "op-1", 99, "b11", 15, "2026-01-01T10:30:00Z",
                                "r1", 1, 10, "2026-01-01T11:00:00Z", "r2", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/equipment/eq-f2/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b12", "", 3, "b12", 15, "2026-01-01T10:30:00Z",
                                "r1", 1, 10, "2026-01-01T11:00:00Z", "r2", 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 未设置基准应力的设备不可登记循环 → 422
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-eq-f2b","equipmentId":"eq-f2b",
                                 "maintenancePeriodMinutes":1000}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-f2b/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f2-b13", "op-1", 1, "b13", 15, "2026-01-01T10:30:00Z",
                                "r1", 1, 10, "2026-01-01T11:00:00Z", "r2", 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FATIGUE_NOT_CONFIGURED"));

        // 全部失败均未落库：无循环、无损伤、版本未前进
        mockMvc.perform(get("/api/equipment/eq-f2/cycles"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-f2/status"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.currentRoundDamage").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    // ---------- 区间重叠、跨锚点与引用读数锁定 ----------

    @Test
    void cycleOverlapAnchorAndReferencedReadingRules() throws Exception {
        register("eq-f3", 100000, 1000000, 10);
        addReading("eq-f3", "f3-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f3", "f3-r2", 2, "r2", "2026-01-01T11:00:00Z", 30);
        addReading("eq-f3", "f3-r3", 3, "r3", "2026-01-01T12:00:00Z", 60);
        addReading("eq-f3", "f3-r4", 4, "r4", "2026-01-01T13:00:00Z", 90);

        addCycle("eq-f3", "f3-c1", 5, "c1", 15, "2026-01-01T10:10:00Z", "r1", 1,
                10, "2026-01-01T10:50:00Z", "r2", 1, 201);

        // 区间重叠 → 422；端点相接不算重叠 → 201
        addCycle("eq-f3", "f3-c2x", 6, "c2x", 15, "2026-01-01T10:40:00Z", "r1", 1,
                10, "2026-01-01T11:10:00Z", "r3", 1, 422);
        addCycle("eq-f3", "f3-c2", 6, "c2", 15, "2026-01-01T10:50:00Z", "r1", 1,
                10, "2026-01-01T11:10:00Z", "r3", 1, 201);

        // 跨未来锚点的循环可登记，但锚点落在其内部时保养被拒绝
        addCycle("eq-f3", "f3-c3", 7, "c3", 20, "2026-01-01T11:30:00Z", "r2", 1,
                10, "2026-01-01T12:30:00Z", "r4", 1, 201);
        mockMvc.perform(post("/api/equipment/eq-f3/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f3-m1","expectedVersion":8,"readingId":"r3","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_SPANS_ANCHOR"));

        // 锚点在所有循环之后：保养成功，完整循环集合冻结为历史
        mockMvc.perform(post("/api/equipment/eq-f3/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f3-m2","expectedVersion":8,"readingId":"r4","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 保养后新循环跨历史锚点 → 422，不能把一个损伤拆进两轮
        addReading("eq-f3", "f3-r5", 9, "r5", "2026-01-01T14:00:00Z", 120);
        mockMvc.perform(post("/api/equipment/eq-f3/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f3-c4", "op-1", 10, "c4", 15, "2026-01-01T12:45:00Z",
                                "r3", 1, 10, "2026-01-01T13:30:00Z", "r5", 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_SPANS_ANCHOR"));

        // 已被循环引用的读数不可修订 → 409；未引用读数可正常修订
        mockMvc.perform(post("/api/equipment/eq-f3/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f3-rv1","expectedVersion":10,"cumulativeMinutes":5}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_CYCLE_REFERENCED"));
        mockMvc.perform(post("/api/equipment/eq-f3/readings/r5/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f3-rv2","expectedVersion":10,"cumulativeMinutes":130}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2));

        // 账本与当前轮损伤核对：3 条循环均归历史轮，当前轮损伤 0
        mockMvc.perform(get("/api/equipment/eq-f3/cycles"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].cycleId").value("c1"))
                .andExpect(jsonPath("$[0].currentRound").value(false))
                .andExpect(jsonPath("$[2].cycleId").value("c3"))
                .andExpect(jsonPath("$[2].damage").value(1000));
        mockMvc.perform(get("/api/equipment/eq-f3/status"))
                .andExpect(jsonPath("$.currentRoundDamage").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    // ---------- 轮次归属：锚点之前的补录循环只归历史轮，不给当前轮加量 ----------

    @Test
    void roundAttribution_backfillBeforeAnchorStaysHistory() throws Exception {
        register("eq-f4", 100000, 1000, 10);
        addReading("eq-f4", "f4-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f4", "f4-r2", 2, "r2", "2026-01-01T11:00:00Z", 30);
        addReading("eq-f4", "f4-r3", 3, "r3", "2026-01-01T12:00:00Z", 60);

        addCycle("eq-f4", "f4-c1", 4, "c1", 15, "2026-01-01T10:10:00Z", "r1", 1,
                10, "2026-01-01T10:30:00Z", "r2", 1, 201);
        mockMvc.perform(get("/api/equipment/eq-f4/status"))
                .andExpect(jsonPath("$.currentRoundDamage").value(125));

        mockMvc.perform(post("/api/equipment/eq-f4/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f4-m1","expectedVersion":5,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-f4/status"))
                .andExpect(jsonPath("$.currentRoundDamage").value(0));

        // 补录锚点之前的循环：损伤 1000 只归历史轮，不触发当前轮 DUE
        addCycle("eq-f4", "f4-c2", 6, "c2", 20, "2026-01-01T10:40:00Z", "r1", 1,
                10, "2026-01-01T10:50:00Z", "r2", 1, 201);
        mockMvc.perform(get("/api/equipment/eq-f4/status"))
                .andExpect(jsonPath("$.currentRoundDamage").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 锚点之后的循环计入当前轮
        addCycle("eq-f4", "f4-c3", 7, "c3", 15, "2026-01-01T11:10:00Z", "r2", 1,
                10, "2026-01-01T11:30:00Z", "r3", 1, 201);
        mockMvc.perform(get("/api/equipment/eq-f4/status"))
                .andExpect(jsonPath("$.currentRoundDamage").value(125))
                .andExpect(jsonPath("$.status").value("OK"));

        mockMvc.perform(get("/api/equipment/eq-f4/cycles"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].currentRound").value(false))
                .andExpect(jsonPath("$[1].currentRound").value(false))
                .andExpect(jsonPath("$[2].currentRound").value(true));
    }

    // ---------- 幂等：同参重放、异参 409、失败不占键 ----------

    @Test
    void idempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-f5", 100000, 125, 10);
        addReading("eq-f5", "f5-r1", 1, "r1", "2026-01-01T10:00:00Z", 0);
        addReading("eq-f5", "f5-r2", 2, "r2", "2026-01-01T11:00:00Z", 30);

        String body = cycleBody("f5-c1", "op-1", 3, "c1", 15, "2026-01-01T10:10:00Z", "r1", 1,
                10, "2026-01-01T10:40:00Z", "r2", 1);
        MvcResult first = postJson("/api/equipment/eq-f5/cycles", body);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        // 同键同参：重放首次完整响应，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-f5/cycles", body);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-f5/cycles"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-f5/status"))
                .andExpect(jsonPath("$.version").value(4));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-f5/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f5-c1", "op-1", 3, "c1", 16, "2026-01-01T10:10:00Z",
                                "r1", 1, 10, "2026-01-01T10:40:00Z", "r2", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 后同 requestId 修正参数可成功
        mockMvc.perform(post("/api/equipment/eq-f5/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f5-c2", "op-1", 4, "c2", 15, "2026-01-01T10:45:00Z",
                                "r1", 1, 12, "2026-01-01T10:55:00Z", "r2", 1)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/equipment/eq-f5/cycles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cycleBody("f5-c2", "op-1", 4, "c2", 15, "2026-01-01T10:45:00Z",
                                "r1", 1, 10, "2026-01-01T10:55:00Z", "r2", 1)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/equipment/eq-f5/cycles"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-f5/status"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.currentRoundDamage").value(250));
    }
}
