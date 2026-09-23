package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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
 * 工时表更换链与跨表修订重算：主流程、只读查询、失败整体回滚、幂等快照（真实 H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeterReplacementTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM meter_chain_recompute");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM meter");
        jdbc.update("DELETE FROM equipment");
    }

    private void register(String equipmentId, long periodMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes)))
                .andExpect(status().isCreated());
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    /** 构造双表链条：m1 读数 100/200 → 更换(final=210, 新表初始=1000) → m2 读数 1050/1100。 */
    private void buildTwoMeterChain() throws Exception {
        register("eq-m", 60000);
        postJson("/api/equipment/eq-m/readings", """
                {"requestId":"r1-add","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":100}
                """);
        postJson("/api/equipment/eq-m/readings", """
                {"requestId":"r2-add","expectedVersion":2,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","rawHours":200}
                """);
        postJson("/api/equipment/eq-m/meters/replacements", """
                {"replacementKey":"rep-1","expectedVersion":3,"newMeterKey":"m2",
                 "finalRawHours":210,"initialRawHours":1000,"lastReadingRevisionNo":1}
                """);
        postJson("/api/equipment/eq-m/readings", """
                {"requestId":"r3-add","expectedVersion":4,"readingId":"r3",
                 "sampledAt":"2026-01-01T12:00:00Z","rawHours":1050}
                """);
        postJson("/api/equipment/eq-m/readings", """
                {"requestId":"r4-add","expectedVersion":5,"readingId":"r4",
                 "sampledAt":"2026-01-01T13:00:00Z","rawHours":1100}
                """);
    }

    // ---------- 主流程：更换冻结偏移，新表读数映射为连续虚拟工时 ----------

    @Test
    void replacement_freezesOffsetAndVirtualHoursStayContinuous() throws Exception {
        buildTwoMeterChain();

        // 旧表 CLOSED（final 210 不可变），新表 ACTIVE，偏移冻结为旧表最后有效读数 200
        mockMvc.perform(get("/api/equipment/eq-m/meters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeMeterKey").value("m2"))
                .andExpect(jsonPath("$.meters", hasSize(2)))
                .andExpect(jsonPath("$.meters[0].meterKey").value("eq-m"))
                .andExpect(jsonPath("$.meters[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.meters[0].finalRawHours").value(210))
                .andExpect(jsonPath("$.meters[0].offsetHours").value(0))
                .andExpect(jsonPath("$.meters[0].recalcVersion").value(1))
                .andExpect(jsonPath("$.meters[1].meterKey").value("m2"))
                .andExpect(jsonPath("$.meters[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.meters[1].seqNo").value(1))
                .andExpect(jsonPath("$.meters[1].predecessorKey").value("eq-m"))
                .andExpect(jsonPath("$.meters[1].replacementKey").value("rep-1"))
                .andExpect(jsonPath("$.meters[1].initialRawHours").value(1000))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(200))
                .andExpect(jsonPath("$.meters[1].recalcVersion").value(1))
                .andExpect(jsonPath("$.recomputes", hasSize(0)));

        // 读数同时给出原始/虚拟工时：新表 virtual = 200 + raw - 1000
        mockMvc.perform(get("/api/equipment/eq-m/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].meterKey").value("eq-m"))
                .andExpect(jsonPath("$[0].rawHours").value(100))
                .andExpect(jsonPath("$[0].virtualHours").value(100))
                .andExpect(jsonPath("$[1].rawHours").value(200))
                .andExpect(jsonPath("$[1].virtualHours").value(200))
                .andExpect(jsonPath("$[2].meterKey").value("m2"))
                .andExpect(jsonPath("$[2].rawHours").value(1050))
                .andExpect(jsonPath("$[2].virtualHours").value(250))
                .andExpect(jsonPath("$[2].virtualMinutes").value(15000))
                .andExpect(jsonPath("$[3].rawHours").value(1100))
                .andExpect(jsonPath("$[3].virtualHours").value(300));

        // 状态跨表连续：最新读数 300h = 18000 分钟
        mockMvc.perform(get("/api/equipment/eq-m/status"))
                .andExpect(jsonPath("$.activeMeterKey").value("m2"))
                .andExpect(jsonPath("$.latestRawHours").value(1100))
                .andExpect(jsonPath("$.latestVirtualHours").value(300))
                .andExpect(jsonPath("$.runMinutes").value(18000));

        // 新表读数不得低于其初始原始读数 1000
        mockMvc.perform(post("/api/equipment/eq-m/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r5-bad2","expectedVersion":6,"readingId":"r5",
                                 "sampledAt":"2026-01-01T14:00:00Z","rawHours":999}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_BELOW_INITIAL"));
    }

    // ---------- 更换失败分支 ----------

    @Test
    void replacement_validationFailures() throws Exception {
        register("eq-r", 60000);
        postJson("/api/equipment/eq-r/readings", """
                {"requestId":"rr1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":100}
                """);

        // finalRawHours 小于旧表最后有效读数 → 422
        mockMvc.perform(post("/api/equipment/eq-r/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-bad1","expectedVersion":2,"newMeterKey":"m2",
                                 "finalRawHours":99,"initialRawHours":0,"lastReadingRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FINAL_BELOW_LAST_READING"));

        // 最后读数版本令牌不一致 → 409
        mockMvc.perform(post("/api/equipment/eq-r/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-bad2","expectedVersion":2,"newMeterKey":"m2",
                                 "finalRawHours":120,"initialRawHours":0,"lastReadingRevisionNo":9}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_READING_VERSION_CONFLICT"));

        // 版本不符 → 409
        mockMvc.perform(post("/api/equipment/eq-r/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-bad3","expectedVersion":99,"newMeterKey":"m2",
                                 "finalRawHours":120,"initialRawHours":0,"lastReadingRevisionNo":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 负值被 Bean Validation 拦截 → 400
        mockMvc.perform(post("/api/equipment/eq-r/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-bad4","expectedVersion":2,"newMeterKey":"m2",
                                 "finalRawHours":-1,"initialRawHours":0,"lastReadingRevisionNo":1}
                                """))
                .andExpect(status().isBadRequest());

        // 全部失败：仍只有初始 ACTIVE 表，版本未前进
        mockMvc.perform(get("/api/equipment/eq-r/meters"))
                .andExpect(jsonPath("$.meters", hasSize(1)))
                .andExpect(jsonPath("$.activeMeterKey").value("eq-r"));
        mockMvc.perform(get("/api/equipment/eq-r/status"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void replacement_meterKeyGloballyUnique_andNoCycle() throws Exception {
        register("eq-a", 60000);
        register("eq-b", 60000);
        // eq-a 使用 meterKey "shared"
        mockMvc.perform(post("/api/equipment/eq-a/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-a","expectedVersion":1,"newMeterKey":"shared",
                                 "finalRawHours":0,"initialRawHours":0}
                                """))
                .andExpect(status().isCreated());
        // 其他设备复用同一 meterKey → 409（meterKey 全局唯一，链不可复用/成环）
        mockMvc.perform(post("/api/equipment/eq-b/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-b","expectedVersion":1,"newMeterKey":"shared",
                                 "finalRawHours":0,"initialRawHours":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_KEY_EXISTS"));
    }

    // ---------- 跨表修订重算 ----------

    @Test
    void reviseClosedMeterLastReading_recalculatesWholeChain() throws Exception {
        buildTwoMeterChain();
        // 在后继表先登记一次保养（锚定 r4，虚拟 300h），验证锚点随重算刷新
        mockMvc.perform(post("/api/equipment/eq-m/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-r4","expectedVersion":6,"readingId":"r4","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorVirtualHours").value(300));

        // 修订旧表最后有效读数 r2：200 → 190（≤ final 210，≥ 前邻 r1=100）
        mockMvc.perform(post("/api/equipment/eq-m/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-r2-1","expectedVersion":7,"rawHours":190}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rawHours").value(190))
                .andExpect(jsonPath("$.virtualHours").value(190))
                .andExpect(jsonPath("$.revisionNo").value(2));

        // 后继表偏移、读数虚拟工时、保养锚点全部平移 -10h；链条连续无重叠
        mockMvc.perform(get("/api/equipment/eq-m/readings"))
                .andExpect(jsonPath("$[1].rawHours").value(190))
                .andExpect(jsonPath("$[1].virtualHours").value(190))
                .andExpect(jsonPath("$[2].rawHours").value(1050))
                .andExpect(jsonPath("$[2].virtualHours").value(240))
                .andExpect(jsonPath("$[3].rawHours").value(1100))
                .andExpect(jsonPath("$[3].virtualHours").value(290));
        mockMvc.perform(get("/api/equipment/eq-m/maintenances"))
                .andExpect(jsonPath("$[0].anchorVirtualHours").value(290))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(17400));

        // 偏移冻结被重算：m2 recalcVersion=2；重算审计追加一条
        mockMvc.perform(get("/api/equipment/eq-m/meters"))
                .andExpect(jsonPath("$.meters[0].recalcVersion").value(1))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(190))
                .andExpect(jsonPath("$.meters[1].recalcVersion").value(2))
                .andExpect(jsonPath("$.recomputes", hasSize(1)))
                .andExpect(jsonPath("$.recomputes[0].recomputeNo").value(1))
                .andExpect(jsonPath("$.recomputes[0].triggeredMeterKey").value("eq-m"))
                .andExpect(jsonPath("$.recomputes[0].triggeredReadingId").value("r2"))
                .andExpect(jsonPath("$.recomputes[0].fromRawHours").value(200))
                .andExpect(jsonPath("$.recomputes[0].toRawHours").value(190));

        // 当前到期状态同步重算：最新 290h，锚点 290h，本轮 0
        mockMvc.perform(get("/api/equipment/eq-m/status"))
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.latestVirtualHours").value(290))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 再向另一个方向重算 190 → 195，第二跳链与第二个重算版本
        mockMvc.perform(post("/api/equipment/eq-m/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-r2-2","expectedVersion":8,"rawHours":195}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(3));
        mockMvc.perform(get("/api/equipment/eq-m/readings"))
                .andExpect(jsonPath("$[2].virtualHours").value(245))
                .andExpect(jsonPath("$[3].virtualHours").value(295));
        mockMvc.perform(get("/api/equipment/eq-m/meters"))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(195))
                .andExpect(jsonPath("$.meters[1].recalcVersion").value(3))
                .andExpect(jsonPath("$.recomputes", hasSize(2)));
    }

    @Test
    void reviseClosedMeter_intermediateReading_doesNotShiftChain() throws Exception {
        buildTwoMeterChain();
        // r1 不是旧表最后有效读数：修订只改自身虚拟工时，后继链不重算
        mockMvc.perform(post("/api/equipment/eq-m/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-r1","expectedVersion":6,"rawHours":120}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-m/meters"))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(200))
                .andExpect(jsonPath("$.meters[1].recalcVersion").value(1))
                .andExpect(jsonPath("$.recomputes", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-m/readings"))
                .andExpect(jsonPath("$[2].virtualHours").value(250));
    }

    // ---------- 整体回滚：422 时原历史不变、无半重算 ----------

    @Test
    void reviseClosedMeter_exceedingFinal_rollsBackEverything() throws Exception {
        buildTwoMeterChain();
        mockMvc.perform(post("/api/equipment/eq-m/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-r4","expectedVersion":6,"readingId":"r4","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 超过该表 finalRawHours(210) → 422
        mockMvc.perform(post("/api/equipment/eq-m/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-over","expectedVersion":7,"rawHours":211}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLOSED_METER_FINAL_EXCEEDED"));

        // 原历史完全不变：读数、后继偏移、锚点、版本、重算审计均无任何写入
        mockMvc.perform(get("/api/equipment/eq-m/readings"))
                .andExpect(jsonPath("$[1].rawHours").value(200))
                .andExpect(jsonPath("$[1].revisionNo").value(1))
                .andExpect(jsonPath("$[2].virtualHours").value(250))
                .andExpect(jsonPath("$[3].virtualHours").value(300));
        mockMvc.perform(get("/api/equipment/eq-m/meters"))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(200))
                .andExpect(jsonPath("$.meters[1].recalcVersion").value(1))
                .andExpect(jsonPath("$.recomputes", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-m/maintenances"))
                .andExpect(jsonPath("$[0].anchorVirtualHours").value(300));
        mockMvc.perform(get("/api/equipment/eq-m/status"))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.runMinutes").value(0));
        Integer revisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_revision WHERE equipment_id = 'eq-m' AND reading_id = 'r2'",
                Integer.class);
        Integer recomputes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter_chain_recompute WHERE equipment_id = 'eq-m'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, revisions, "失败修订不得追加修订历史");
        org.junit.jupiter.api.Assertions.assertEquals(0, recomputes, "失败重算不得留下审计/半重算");

        // 低于表内前邻读数同样 422 且不产生重算
        mockMvc.perform(post("/api/equipment/eq-m/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-low","expectedVersion":7,"rawHours":90}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));
        mockMvc.perform(get("/api/equipment/eq-m/meters"))
                .andExpect(jsonPath("$.recomputes", hasSize(0)));
    }

    @Test
    void reviseClosedMeter_anchoredReading_conflict() throws Exception {
        buildTwoMeterChain();
        mockMvc.perform(post("/api/equipment/eq-m/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-r2","expectedVersion":6,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        // 已作为保养锚点的旧表读数不可修订
        mockMvc.perform(post("/api/equipment/eq-m/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-anchor","expectedVersion":7,"rawHours":205}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));
    }

    // ---------- 整链回滚：重算使已记录保养落到未来工时 → 422 原历史不变 ----------

    @Test
    void recompute_maintenanceInFuture_rollsBackWholeChain() throws Exception {
        register("eq-fut", 600000);
        // 旧表：较早采样时刻的低读数
        postJson("/api/equipment/eq-fut/readings", """
                {"requestId":"fa1","expectedVersion":1,"readingId":"a1",
                 "sampledAt":"2026-01-01T09:00:00Z","rawHours":100}
                """);
        postJson("/api/equipment/eq-fut/readings", """
                {"requestId":"fa2","expectedVersion":2,"readingId":"a2",
                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":200}
                """);
        mockMvc.perform(post("/api/equipment/eq-fut/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-fut","expectedVersion":3,"newMeterKey":"mf2",
                                 "finalRawHours":260,"initialRawHours":1000,"lastReadingRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        // 后继表补录一条采样时刻更早、但虚拟工时更高的读数（时间与工时无需同序，业务允许）
        postJson("/api/equipment/eq-fut/readings", """
                {"requestId":"fb1","expectedVersion":4,"readingId":"b1",
                 "sampledAt":"2026-01-01T06:00:00Z","rawHours":1200}
                """);
        // 在 b1 上完成保养：锚点虚拟 400h
        mockMvc.perform(post("/api/equipment/eq-fut/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-fut","expectedVersion":5,"readingId":"b1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorVirtualHours").value(400));

        // 上修旧表最后有效读数 200 → 250：后继偏移 +50，锚点被推到 450，
        // 而采样时刻最新读数仍是 a2（虚拟 250）→ 保养落入未来工时，必须 422
        mockMvc.perform(post("/api/equipment/eq-fut/readings/a2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-fut","expectedVersion":6,"rawHours":250}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECOMPUTE_MAINTENANCE_IN_FUTURE"));

        // 原历史不变：偏移、读数虚拟工时、锚点快照、版本、审计均回滚
        // 读数按采样时刻升序：b1(06:00,m2) / a1(09:00,旧表) / a2(10:00,旧表)
        mockMvc.perform(get("/api/equipment/eq-fut/meters"))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(200))
                .andExpect(jsonPath("$.meters[1].recalcVersion").value(1))
                .andExpect(jsonPath("$.recomputes", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-fut/readings"))
                .andExpect(jsonPath("$[0].readingId").value("b1"))
                .andExpect(jsonPath("$[0].virtualHours").value(400))
                .andExpect(jsonPath("$[2].readingId").value("a2"))
                .andExpect(jsonPath("$[2].virtualHours").value(200));
        mockMvc.perform(get("/api/equipment/eq-fut/maintenances"))
                .andExpect(jsonPath("$[0].anchorVirtualHours").value(400));
        mockMvc.perform(get("/api/equipment/eq-fut/status"))
                .andExpect(jsonPath("$.version").value(6));
    }

    // ---------- 三表链条：重算沿全部后继表传播 ----------

    @Test
    void recompute_propagatesAcrossAllSuccessors() throws Exception {
        buildTwoMeterChain();
        // 第二次更换：m2 最后读数 1100（虚拟 300），final=1120，m3 初始 5000
        mockMvc.perform(post("/api/equipment/eq-m/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-2","expectedVersion":6,"newMeterKey":"m3",
                                 "finalRawHours":1120,"initialRawHours":5000,"lastReadingRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        postJson("/api/equipment/eq-m/readings", """
                {"requestId":"r6-add","expectedVersion":7,"readingId":"r6",
                 "sampledAt":"2026-01-01T14:00:00Z","rawHours":5050}
                """);
        // r6 虚拟 = 300 + 50 = 350
        mockMvc.perform(get("/api/equipment/eq-m/status"))
                .andExpect(jsonPath("$.latestVirtualHours").value(350));

        // 修订 m1 最后读数 200 → 180：m2 偏移 180、m3 偏移 280，r3/r4/r6 全部平移 -20
        mockMvc.perform(post("/api/equipment/eq-m/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-chain","expectedVersion":8,"rawHours":180}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-m/meters"))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(180))
                .andExpect(jsonPath("$.meters[1].recalcVersion").value(2))
                .andExpect(jsonPath("$.meters[2].meterKey").value("m3"))
                .andExpect(jsonPath("$.meters[2].offsetHours").value(280))
                .andExpect(jsonPath("$.meters[2].recalcVersion").value(2));
        mockMvc.perform(get("/api/equipment/eq-m/readings"))
                .andExpect(jsonPath("$[1].virtualHours").value(180))
                .andExpect(jsonPath("$[2].virtualHours").value(230))
                .andExpect(jsonPath("$[3].virtualHours").value(280))
                .andExpect(jsonPath("$[4].virtualHours").value(330));
    }

    // ---------- 幂等：同参重放完整链快照，异参 409，失败不占键 ----------

    @Test
    void replacement_idempotentSnapshotMismatchAndFailureNotOccupying() throws Exception {
        register("eq-idem", 60000);
        postJson("/api/equipment/eq-idem/readings", """
                {"requestId":"ir1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":100}
                """);

        String replacement = """
                {"replacementKey":"rep-idem","expectedVersion":2,"newMeterKey":"mi2",
                 "finalRawHours":110,"initialRawHours":500,"lastReadingRevisionNo":1}
                """;
        MvcResult first = postJson("/api/equipment/eq-idem/meters/replacements", replacement);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        // 同参重放：返回首次的完整链快照（JSON 完全一致，含当时版本与链条）
        MvcResult replay = postJson("/api/equipment/eq-idem/meters/replacements", replacement);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-idem/meters"))
                .andExpect(jsonPath("$.meters", hasSize(2)));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-idem/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-idem","expectedVersion":2,"newMeterKey":"mi2",
                                 "finalRawHours":111,"initialRawHours":500,"lastReadingRevisionNo":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：另一设备上先制造 422，再用同一 replacementKey 修正后成功
        register("eq-idem2", 60000);
        postJson("/api/equipment/eq-idem2/readings", """
                {"requestId":"ir2","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":100}
                """);
        mockMvc.perform(post("/api/equipment/eq-idem2/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-retry","expectedVersion":2,"newMeterKey":"mj2",
                                 "finalRawHours":90,"initialRawHours":0,"lastReadingRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/equipment/eq-idem2/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-retry","expectedVersion":2,"newMeterKey":"mj2",
                                 "finalRawHours":100,"initialRawHours":0,"lastReadingRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
    }

    // ---------- 小数工时与跨表到期判定 ----------

    @Test
    void fractionalHours_dueStatusAcrossMeters() throws Exception {
        // 保养周期 65 分钟：1.5 小时 = 90 分钟应判 DUE
        register("eq-frac", 65);
        postJson("/api/equipment/eq-frac/readings", """
                {"requestId":"f1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":1.5}
                """);
        mockMvc.perform(get("/api/equipment/eq-frac/status"))
                .andExpect(jsonPath("$.latestVirtualMinutes").value(90))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 更换后跨表累计仍连续：m2 初始 100.25，读数 101.25 → 虚拟 2.5h = 150min
        mockMvc.perform(post("/api/equipment/eq-frac/meters/replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacementKey":"rep-f","expectedVersion":2,"newMeterKey":"mf2",
                                 "finalRawHours":1.75,"initialRawHours":100.25,"lastReadingRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.closedMeter.finalRawHours").value(1.75))
                .andExpect(jsonPath("$.activeMeter.offsetHours").value(1.5));
        postJson("/api/equipment/eq-frac/readings", """
                {"requestId":"f2","expectedVersion":3,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","rawHours":101.25}
                """);
        mockMvc.perform(get("/api/equipment/eq-frac/status"))
                .andExpect(jsonPath("$.latestRawHours").value(101.25))
                .andExpect(jsonPath("$.latestVirtualHours").value(2.5))
                .andExpect(jsonPath("$.latestVirtualMinutes").value(150))
                .andExpect(jsonPath("$.runMinutes").value(150))
                .andExpect(jsonPath("$.status").value("DUE"));

        BigDecimal storedOffset = jdbc.queryForObject(
                "SELECT offset_hours FROM meter WHERE equipment_id = 'eq-frac' AND meter_key = 'mf2'",
                BigDecimal.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, storedOffset.compareTo(new BigDecimal("1.500000")));
    }
}
