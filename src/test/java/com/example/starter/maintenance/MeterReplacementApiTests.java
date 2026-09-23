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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 工时表更换链 API 测试：更换登记、虚拟工时连续性、跨表修订重算、整体回滚、幂等与只读查询。
 * 真实 H2 内存库（MySQL 兼容模式），每个用例前清空全部业务表。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeterReplacementApiTests {

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
        jdbc.update("DELETE FROM meter_replacement");
        jdbc.update("DELETE FROM meter");
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
                .andExpect(jsonPath("$.version").value(1));
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

    private ResultActions replace(String equipmentId, String requestId, long expectedVersion,
                                  String replacementKey, String newMeterKey, int oldLastReadingVersion,
                                  long finalRawHours, long initialRawHours) throws Exception {
        return mockMvc.perform(post("/api/equipment/" + equipmentId + "/meter-replacements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","expectedVersion":%d,"replacementKey":"%s","newMeterKey":"%s",
                         "oldLastReadingVersion":%d,"finalRawHours":%d,"initialRawHours":%d}
                        """.formatted(requestId, expectedVersion, replacementKey, newMeterKey,
                        oldLastReadingVersion, finalRawHours, initialRawHours)));
    }

    private ResultActions revise(String equipmentId, String readingId, String requestId,
                                 long expectedVersion, long cumulativeMinutes) throws Exception {
        return mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings/" + readingId
                        + "/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, cumulativeMinutes)));
    }

    // ---------- 主流程：更换 → 新表读数虚拟连续 → 保养 → 只读查询 ----------

    @Test
    void replacementMainFlow_virtualContinuityAndChainSnapshot() throws Exception {
        register("eq-rp", 1000);
        addReading("eq-rp", "rp-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-rp", "rp-a2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 更换：旧表最后读数 r2(版本1, 值200)，申报 final=200，新表 meter-B 起始 50
        // 冻结 offset = virtual(200) - 50 = 150
        mockMvc.perform(post("/api/equipment/eq-rp/meter-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rp-rep1","expectedVersion":3,"replacementKey":"rk-1",
                                 "newMeterKey":"meter-B","oldLastReadingVersion":1,
                                 "finalRawHours":200,"initialRawHours":50}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentVersion").value(4))
                .andExpect(jsonPath("$.recalcVersion").value(0))
                .andExpect(jsonPath("$.meters", hasSize(2)))
                .andExpect(jsonPath("$.meters[0].meterKey").value("eq-rp#meter-0"))
                .andExpect(jsonPath("$.meters[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.meters[0].finalRawHours").value(200))
                .andExpect(jsonPath("$.meters[0].offsetHours").value(0))
                .andExpect(jsonPath("$.meters[0].lastRawHours").value(200))
                .andExpect(jsonPath("$.meters[0].lastVirtualHours").value(200))
                .andExpect(jsonPath("$.meters[1].meterKey").value("meter-B"))
                .andExpect(jsonPath("$.meters[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.meters[1].initialRawHours").value(50))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(150))
                .andExpect(jsonPath("$.meters[1].baseVirtualHours").value(200))
                .andExpect(jsonPath("$.replacements", hasSize(1)))
                .andExpect(jsonPath("$.replacements[0].replacementKey").value("rk-1"))
                .andExpect(jsonPath("$.replacements[0].oldMeterKey").value("eq-rp#meter-0"))
                .andExpect(jsonPath("$.replacements[0].newMeterKey").value("meter-B"))
                .andExpect(jsonPath("$.replacements[0].offsetHours").value(150));

        // 新表读数不得低于起始读数（否则跨表重叠）
        addReading("eq-rp", "rp-a3x", 4, "r3", "2026-01-01T12:00:00Z", 40, 422);

        // 新表读数 raw=80 → 虚拟工时 80+150=230，与旧表连续
        mockMvc.perform(post("/api/equipment/eq-rp/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rp-a3","expectedVersion":4,"readingId":"r3",
                                 "sampledAt":"2026-01-01T12:00:00Z","cumulativeMinutes":80}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meterKey").value("meter-B"))
                .andExpect(jsonPath("$.cumulativeMinutes").value(80))
                .andExpect(jsonPath("$.virtualHours").value(230));

        // 状态基于虚拟工时：本轮运行 230（尚无保养）
        mockMvc.perform(get("/api/equipment/eq-rp/status"))
                .andExpect(jsonPath("$.activeMeterKey").value("meter-B"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(80))
                .andExpect(jsonPath("$.latestVirtualHours").value(230))
                .andExpect(jsonPath("$.runMinutes").value(230))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.recalcVersion").value(0));

        // 在新表上完成保养：锚点虚拟工时 230
        mockMvc.perform(post("/api/equipment/eq-rp/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rp-m1","expectedVersion":5,"readingId":"r3","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meterKey").value("meter-B"))
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(80))
                .andExpect(jsonPath("$.anchorVirtualHours").value(230));

        mockMvc.perform(get("/api/equipment/eq-rp/status"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorVirtualHours").value(230))
                .andExpect(jsonPath("$.runMinutes").value(0));

        // 原始/虚拟读数只读查询：跨表按链序连续
        mockMvc.perform(get("/api/equipment/eq-rp/readings"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].meterKey").value("eq-rp#meter-0"))
                .andExpect(jsonPath("$[0].virtualHours").value(100))
                .andExpect(jsonPath("$[1].virtualHours").value(200))
                .andExpect(jsonPath("$[2].readingId").value("r3"))
                .andExpect(jsonPath("$[2].meterKey").value("meter-B"))
                .andExpect(jsonPath("$[2].virtualHours").value(230))
                .andExpect(jsonPath("$[2].anchored").value(true));

        // 更换链与重算版本只读查询
        mockMvc.perform(get("/api/equipment/eq-rp/meter-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipmentVersion").value(6))
                .andExpect(jsonPath("$.recalcVersion").value(0))
                .andExpect(jsonPath("$.meters", hasSize(2)))
                .andExpect(jsonPath("$.replacements", hasSize(1)));
    }

    // ---------- 更换登记失败分支 ----------

    @Test
    void replacementValidationBranches() throws Exception {
        register("eq-val", 100);

        // 当前表没有任何读数 → 422
        replace("eq-val", "v-rep-0", 1, "rk-v0", "m-X", 1, 0, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("METER_NO_READINGS"));

        addReading("eq-val", "v-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);

        // 申报 final 小于旧表最后有效读数 → 422
        replace("eq-val", "v-rep-1", 2, "rk-v1", "m-B", 1, 90, 0)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FINAL_RAW_BELOW_LAST_READING"));
        // 两值非负 → 400
        replace("eq-val", "v-rep-2", 2, "rk-v1", "m-B", 1, -1, 0)
                .andExpect(status().isBadRequest());
        replace("eq-val", "v-rep-3", 2, "rk-v1", "m-B", 1, 100, -5)
                .andExpect(status().isBadRequest());
        // 旧表最后读数版本不一致 → 409
        replace("eq-val", "v-rep-4", 2, "rk-v1", "m-B", 7, 100, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_LAST_READING_VERSION_CONFLICT"));
        // 设备版本冲突 → 409
        replace("eq-val", "v-rep-5", 99, "rk-v1", "m-B", 1, 100, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // 设备不存在 → 404
        replace("nope", "v-rep-6", 1, "rk-v1", "m-B", 1, 100, 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));

        // 全部失败均不改变状态：版本仍为 2，无更换记录
        mockMvc.perform(get("/api/equipment/eq-val/meter-chain"))
                .andExpect(jsonPath("$.equipmentVersion").value(2))
                .andExpect(jsonPath("$.meters", hasSize(1)))
                .andExpect(jsonPath("$.replacements", hasSize(0)));

        // 成功更换：offset = 100 - 0 = 100
        replace("eq-val", "v-rep-7", 2, "rk-v1", "m-B", 1, 100, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meters[1].offsetHours").value(100));

        // 新表补一条读数，使后续更换校验推进到唯一键检查
        addReading("eq-val", "v-a2", 3, "r2", "2026-01-01T11:00:00Z", 30, 201);

        // replacementKey 唯一 → 409
        replace("eq-val", "v-rep-8", 4, "rk-v1", "m-C", 1, 30, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPLACEMENT_KEY_EXISTS"));
        // meterKey 唯一（含已关闭表，保证链不成环）→ 409
        replace("eq-val", "v-rep-9", 4, "rk-v2", "m-B", 1, 30, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_KEY_EXISTS"));
        replace("eq-val", "v-rep-10", 4, "rk-v2", "eq-val#meter-0", 1, 30, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_KEY_EXISTS"));

        // 二次更换：m-B → m-C，链上始终仅一张 ACTIVE 表
        replace("eq-val", "v-rep-11", 4, "rk-v3", "m-C", 1, 30, 10)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meters", hasSize(3)))
                .andExpect(jsonPath("$.meters[1].status").value("CLOSED"))
                .andExpect(jsonPath("$.meters[1].finalRawHours").value(30))
                .andExpect(jsonPath("$.meters[2].status").value("ACTIVE"))
                .andExpect(jsonPath("$.meters[2].offsetHours").value(120));

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meter WHERE equipment_id = 'eq-val' AND status = 'ACTIVE'",
                Integer.class);
        assertEquals(1, activeCount, "同设备只能有一张 ACTIVE 表");
    }

    // ---------- 跨表修订触发全链重算 ----------

    @Test
    void revisionOnClosedMeter_recomputesWholeChainAtomically() throws Exception {
        register("eq-rc", 1000);
        addReading("eq-rc", "rc-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-rc", "rc-a2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        // meter-0 → meter-B：offset 150
        replace("eq-rc", "rc-rep1", 3, "rk-1", "meter-B", 1, 200, 50)
                .andExpect(status().isCreated());
        addReading("eq-rc", "rc-a3", 4, "r3", "2026-01-01T12:00:00Z", 80, 201);
        // meter-B → meter-C：offset = (80+150) - 0 = 230
        replace("eq-rc", "rc-rep2", 5, "rk-2", "meter-C", 1, 80, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meters[2].offsetHours").value(230));
        addReading("eq-rc", "rc-a4", 6, "r4", "2026-01-01T13:00:00Z", 10, 201);
        // 保养锚定 r4：锚点虚拟工时 10+230=240
        mockMvc.perform(post("/api/equipment/eq-rc/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rc-m1","expectedVersion":7,"readingId":"r4","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorVirtualHours").value(240));

        // 修订 meter-0 最后有效读数 r2：200 → 150，触发全链重算
        // offset(B) = 150-50 = 100；r3 虚拟 180；offset(C) = 180-0 = 180；r4 虚拟 190；锚点 → 190
        revise("eq-rc", "r2", "rc-rv1", 8, 150)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.virtualHours").value(150));

        mockMvc.perform(get("/api/equipment/eq-rc/meter-chain"))
                .andExpect(jsonPath("$.recalcVersion").value(1))
                .andExpect(jsonPath("$.meters[0].lastVirtualHours").value(150))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(100))
                .andExpect(jsonPath("$.meters[1].lastVirtualHours").value(180))
                .andExpect(jsonPath("$.meters[2].offsetHours").value(180))
                .andExpect(jsonPath("$.meters[2].lastVirtualHours").value(190));

        mockMvc.perform(get("/api/equipment/eq-rc/readings"))
                .andExpect(jsonPath("$[2].virtualHours").value(180))
                .andExpect(jsonPath("$[3].virtualHours").value(190));

        // 保养锚点虚拟工时同事务重算，原始快照不变
        mockMvc.perform(get("/api/equipment/eq-rc/maintenances"))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(10))
                .andExpect(jsonPath("$[0].anchorVirtualHours").value(190));

        // 到期状态随重算更新
        mockMvc.perform(get("/api/equipment/eq-rc/status"))
                .andExpect(jsonPath("$.latestVirtualHours").value(190))
                .andExpect(jsonPath("$.lastMaintenanceAnchorVirtualHours").value(190))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.recalcVersion").value(1));

        // 修订非最后读数 r1：不触发重算，recalcVersion 不变
        revise("eq-rc", "r1", "rc-rv2", 9, 120)
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-rc/meter-chain"))
                .andExpect(jsonPath("$.recalcVersion").value(1))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(100));

        // 修订中间表 meter-B 的最后有效读数 r3：80 → 70，仅重算其后继
        // offset(C) = (70+100) - 0 = 170；r4 虚拟 180；锚点 → 180
        revise("eq-rc", "r3", "rc-rv3", 10, 70)
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-rc/meter-chain"))
                .andExpect(jsonPath("$.recalcVersion").value(2))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(100))
                .andExpect(jsonPath("$.meters[1].lastVirtualHours").value(170))
                .andExpect(jsonPath("$.meters[2].offsetHours").value(170));
        mockMvc.perform(get("/api/equipment/eq-rc/maintenances"))
                .andExpect(jsonPath("$[0].anchorVirtualHours").value(180));
    }

    // ---------- 修订边界与整体回滚 ----------

    @Test
    void revisionBounds_violationsRollBackCompletely() throws Exception {
        register("eq-rb", 1000);
        addReading("eq-rb", "rb-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-rb", "rb-a2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        replace("eq-rb", "rb-rep1", 3, "rk-1", "meter-B", 1, 200, 50)
                .andExpect(status().isCreated());
        addReading("eq-rb", "rb-a3", 4, "r3", "2026-01-01T12:00:00Z", 80, 201);

        // 已关闭表修订不得超过申报 final（200）→ 422，原历史不变
        revise("eq-rb", "r2", "rb-rv1", 5, 250)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVISION_EXCEEDS_METER_FINAL"));
        // 修订不得低于所属表起始读数（跨表重叠）→ 422
        revise("eq-rb", "r3", "rb-rv2", 5, 40)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_BELOW_METER_INITIAL"));
        // 已关闭表内单调性仍然生效：低于前邻（r1=100）→ 422
        revise("eq-rb", "r2", "rb-rv3", 5, 50)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));

        // 整体回滚：读数、修订历史、offset、重算版本、设备版本全部保持原值
        mockMvc.perform(get("/api/equipment/eq-rb/readings"))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(200))
                .andExpect(jsonPath("$[1].revisionNo").value(1))
                .andExpect(jsonPath("$[2].cumulativeMinutes").value(80));
        mockMvc.perform(get("/api/equipment/eq-rb/readings/r2/revisions"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-rb/meter-chain"))
                .andExpect(jsonPath("$.equipmentVersion").value(5))
                .andExpect(jsonPath("$.recalcVersion").value(0))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(150));

        // 合法修订随后仍可成功并触发重算
        revise("eq-rb", "r2", "rb-rv4", 5, 150)
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-rb/meter-chain"))
                .andExpect(jsonPath("$.recalcVersion").value(1))
                .andExpect(jsonPath("$.meters[1].offsetHours").value(100))
                .andExpect(jsonPath("$.meters[1].lastVirtualHours").value(180));
    }

    // ---------- 更换幂等 ----------

    @Test
    void replacementIdempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-id", 1000);
        addReading("eq-id", "id-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);

        String body = """
                {"requestId":"rid-1","expectedVersion":2,"replacementKey":"rk-i1",
                 "newMeterKey":"m-B","oldLastReadingVersion":1,"finalRawHours":100,"initialRawHours":0}
                """;
        MvcResult first = postJson("/api/equipment/eq-id/meter-replacements", body);
        assertEquals(201, first.getResponse().getStatus());

        // 同键同参：重放首次完整链快照，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-id/meter-replacements", body);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-id/meter-chain"))
                .andExpect(jsonPath("$.equipmentVersion").value(3))
                .andExpect(jsonPath("$.meters", hasSize(2)))
                .andExpect(jsonPath("$.replacements", hasSize(1)));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-id/meter-replacements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rid-1","expectedVersion":2,"replacementKey":"rk-i1",
                                 "newMeterKey":"m-B","oldLastReadingVersion":1,"finalRawHours":150,"initialRawHours":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：当前表补一条读数后，422 的同 requestId 修正参数可成功
        addReading("eq-id", "id-a2", 3, "r2", "2026-01-01T11:00:00Z", 10, 201);
        replace("eq-id", "rid-2", 4, "rk-i2", "m-C", 1, 5, 0)
                .andExpect(status().isUnprocessableEntity());
        replace("eq-id", "rid-2", 4, "rk-i2", "m-C", 1, 10, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meters", hasSize(3)));
    }

    // ---------- 已关闭表不可作为保养锚点 ----------

    @Test
    void maintenance_mustAnchorActiveMeter() throws Exception {
        register("eq-mc", 1000);
        addReading("eq-mc", "mc-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        replace("eq-mc", "mc-rep1", 2, "rk-1", "m-B", 1, 100, 0)
                .andExpect(status().isCreated());
        addReading("eq-mc", "mc-a2", 3, "r2", "2026-01-01T11:00:00Z", 10, 201);

        // 已关闭表的读数不可作为新保养锚点 → 422
        mockMvc.perform(post("/api/equipment/eq-mc/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mc-m1","expectedVersion":4,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_METER_NOT_ACTIVE"));

        // 当前 ACTIVE 表读数可锚定，锚点虚拟工时 = 10 + 100
        mockMvc.perform(post("/api/equipment/eq-mc/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mc-m2","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(10))
                .andExpect(jsonPath("$.anchorVirtualHours").value(110));
    }

    // ---------- 只读查询 404 ----------

    @Test
    void meterChain_notFound() throws Exception {
        mockMvc.perform(get("/api/equipment/nope/meter-chain"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
    }
}
