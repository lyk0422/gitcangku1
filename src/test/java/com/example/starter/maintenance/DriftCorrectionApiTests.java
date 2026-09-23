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
 * 漂移修正 API 测试（真实 H2 内存库，MySQL 兼容模式）：插值、单调边界、冻结点、
 * 保养窗口原子重算、幂等与证据查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriftCorrectionApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM drift_correction_item");
        jdbc.update("DELETE FROM drift_correction_anchor");
        jdbc.update("DELETE FROM drift_correction");
        jdbc.update("DELETE FROM maintenance_snapshot");
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
                .andExpect(status().isCreated());
    }

    private void addReading(String equipmentId, String readingId, long expectedVersion,
                            String sampledAt, long cumulativeMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"add-%s-%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(equipmentId, readingId, expectedVersion, readingId,
                                sampledAt, cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    private static String anchor(String readingId, int expectedVersion, String hours) {
        return "{\"readingId\":\"%s\",\"expectedVersion\":%d,\"cumulativeHours\":%s}"
                .formatted(readingId, expectedVersion, hours);
    }

    private MvcResult preview(String equipmentId, String correctionKey, String... anchors)
            throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/drift-corrections/preview", """
                {"correctionKey":"%s","anchors":[%s]}
                """.formatted(correctionKey, String.join(",", anchors)));
    }

    private MvcResult activate(String equipmentId, String requestId, String correctionKey,
                               long expectedVersion, String... anchors) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/drift-corrections", """
                {"requestId":"%s","correctionKey":"%s","expectedVersion":%d,"anchors":[%s]}
                """.formatted(requestId, correctionKey, expectedVersion,
                String.join(",", anchors)));
    }

    /** 标准读数序列：r0=0min（区间外），r1..r5 区间内，r6=240min（区间外）。 */
    private void seedStandardReadings(String equipmentId) throws Exception {
        addReading(equipmentId, "r0", 1, "2026-01-01T09:00:00Z", 0);
        addReading(equipmentId, "r1", 2, "2026-01-01T10:00:00Z", 60);
        addReading(equipmentId, "r2", 3, "2026-01-01T10:30:00Z", 90);
        addReading(equipmentId, "r3", 4, "2026-01-01T11:00:00Z", 120);
        addReading(equipmentId, "r4", 5, "2026-01-01T11:30:00Z", 150);
        addReading(equipmentId, "r5", 6, "2026-01-01T12:00:00Z", 180);
        addReading(equipmentId, "r6", 7, "2026-01-01T13:00:00Z", 240);
    }

    // ---------- 预览：插值段、锚点规范化、不写数据 ----------

    @Test
    void preview_interpolationSegmentsAndNoWrites() throws Exception {
        register("eq-prev", 100);
        seedStandardReadings("eq-prev");

        // 锚点乱序提交：r5, r1, r3 → 按采样时刻规范化为 r1, r3, r5
        mockMvc.perform(post("/api/equipment/eq-prev/drift-corrections/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"correctionKey":"ck-prev","anchors":[%s,%s,%s]}
                                """.formatted(anchor("r5", 1, "3.300"), anchor("r1", 1, "1.000"),
                                anchor("r3", 1, "2.100"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anchorCount").value(3))
                .andExpect(jsonPath("$.anchors[0].readingId").value("r1"))
                .andExpect(jsonPath("$.anchors[0].seq").value(1))
                .andExpect(jsonPath("$.anchors[0].cumulativeHours").value("1.000"))
                .andExpect(jsonPath("$.anchors[0].cumulativeMillis").value(3600000))
                .andExpect(jsonPath("$.anchors[1].readingId").value("r3"))
                .andExpect(jsonPath("$.anchors[1].cumulativeHours").value("2.100"))
                .andExpect(jsonPath("$.anchors[2].readingId").value("r5"))
                .andExpect(jsonPath("$.anchors[2].cumulativeHours").value("3.300"))
                // 区间内 5 条读数：旧值、新值、插值段
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].readingId").value("r1"))
                .andExpect(jsonPath("$.items[0].segmentIndex").value(1))
                .andExpect(jsonPath("$.items[0].oldCumulativeMinutes").value(60))
                .andExpect(jsonPath("$.items[0].oldCumulativeMillis").value(3600000))
                .andExpect(jsonPath("$.items[0].newCumulativeMillis").value(3600000))
                .andExpect(jsonPath("$.items[0].newCumulativeHours").value("1.000"))
                .andExpect(jsonPath("$.items[0].oldRevisionNo").value(1))
                .andExpect(jsonPath("$.items[0].newRevisionNo").value(2))
                // r2 位于段 1（r1→r3）中点：1.000 + (2.100-1.000)*0.5 = 1.550h
                .andExpect(jsonPath("$.items[1].readingId").value("r2"))
                .andExpect(jsonPath("$.items[1].segmentIndex").value(1))
                .andExpect(jsonPath("$.items[1].newCumulativeHours").value("1.550"))
                .andExpect(jsonPath("$.items[1].newCumulativeMillis").value(5580000))
                // r3 为段 1 右端锚点：取校准值 2.100h
                .andExpect(jsonPath("$.items[2].readingId").value("r3"))
                .andExpect(jsonPath("$.items[2].segmentIndex").value(1))
                .andExpect(jsonPath("$.items[2].newCumulativeHours").value("2.100"))
                // r4 位于段 2（r3→r5）中点：2.100 + (3.300-2.100)*0.5 = 2.700h
                .andExpect(jsonPath("$.items[3].readingId").value("r4"))
                .andExpect(jsonPath("$.items[3].segmentIndex").value(2))
                .andExpect(jsonPath("$.items[3].newCumulativeHours").value("2.700"))
                .andExpect(jsonPath("$.items[3].newCumulativeMillis").value(9720000))
                // r5 末锚点归属最后一段
                .andExpect(jsonPath("$.items[4].readingId").value("r5"))
                .andExpect(jsonPath("$.items[4].segmentIndex").value(2))
                .andExpect(jsonPath("$.items[4].newCumulativeHours").value("3.300"))
                // 受影响保养项目：最新读数 r6 在区间外不变，前后状态一致
                .andExpect(jsonPath("$.maintenanceItems", hasSize(1)))
                .andExpect(jsonPath("$.maintenanceItems[0].itemKey").value("MAIN"))
                .andExpect(jsonPath("$.maintenanceItems[0].periodMinutes").value(100))
                .andExpect(jsonPath("$.maintenanceItems[0].runMillisBefore").value(14400000))
                .andExpect(jsonPath("$.maintenanceItems[0].statusBefore").value("DUE"))
                .andExpect(jsonPath("$.maintenanceItems[0].runMillisAfter").value(14400000))
                .andExpect(jsonPath("$.maintenanceItems[0].statusAfter").value("DUE"))
                .andExpect(jsonPath("$.maintenanceItems[0].nextThresholdMillisAfter")
                        .value(6000000));

        // 预览不写数据：读数未变、无修正单、无快照、不占 correctionKey
        mockMvc.perform(get("/api/equipment/eq-prev/readings"))
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[2].readingId").value("r2"))
                .andExpect(jsonPath("$[2].revisionNo").value(1))
                .andExpect(jsonPath("$[2].cumulativeMinutes").value(90));
        mockMvc.perform(get("/api/equipment/eq-prev/drift-corrections"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-prev/maintenance-snapshots"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 预览不占 correctionKey：同键可正常激活
        MvcResult afterPreview = activate("eq-prev", "dc-prev-1", "ck-prev", 8,
                anchor("r1", 1, "1.000"), anchor("r3", 1, "2.100"), anchor("r5", 1, "3.300"));
        assertEquals(201, afterPreview.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-prev/drift-corrections"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].correctionKey").value("ck-prev"));
    }

    // ---------- 激活：新修订、旧版本不可变、保养窗口一次性重算 ----------

    @Test
    void activate_newRevisionsAndAtomicMaintenanceRecompute() throws Exception {
        register("eq-act", 190);
        addReading("eq-act", "r0", 1, "2026-01-01T09:00:00Z", 0);
        addReading("eq-act", "r1", 2, "2026-01-01T10:00:00Z", 60);
        addReading("eq-act", "r2", 3, "2026-01-01T10:30:00Z", 90);
        addReading("eq-act", "r3", 4, "2026-01-01T11:00:00Z", 120);
        addReading("eq-act", "r4", 5, "2026-01-01T11:30:00Z", 150);
        addReading("eq-act", "r5", 6, "2026-01-01T12:00:00Z", 180);

        // 修正前：180 < 190 → OK
        mockMvc.perform(get("/api/equipment/eq-act/status"))
                .andExpect(jsonPath("$.runMinutes").value(180))
                .andExpect(jsonPath("$.status").value("OK"));

        mockMvc.perform(post("/api/equipment/eq-act/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-act-1","correctionKey":"ck-act","expectedVersion":7,
                                 "anchors":[%s,%s,%s]}
                                """.formatted(anchor("r1", 1, "1.000"), anchor("r3", 1, "2.100"),
                                anchor("r5", 1, "3.300"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctionKey").value("ck-act"))
                .andExpect(jsonPath("$.anchorCount").value(3))
                .andExpect(jsonPath("$.affectedCount").value(5))
                .andExpect(jsonPath("$.maintenanceSnapshotVersion").value(1))
                .andExpect(jsonPath("$.equipmentVersion").value(8))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[4].newCumulativeHours").value("3.300"))
                // 保养项目按修正后最新值（r5=198min）一次性重算：NOT_DUE → DUE
                .andExpect(jsonPath("$.maintenanceItems[0].statusBefore").value("NOT_DUE"))
                .andExpect(jsonPath("$.maintenanceItems[0].statusAfter").value("DUE"))
                .andExpect(jsonPath("$.maintenanceItems[0].runMillisAfter").value(11880000))
                .andExpect(jsonPath("$.maintenanceItems[0].nextThresholdMillisAfter")
                        .value(11400000));

        // 每条受影响读数生成新版本，旧版本不可变
        mockMvc.perform(get("/api/equipment/eq-act/readings/r3/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(120))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(126));
        Long millis = jdbc.queryForObject(
                "SELECT cumulative_millis FROM reading WHERE equipment_id = 'eq-act'"
                        + " AND reading_id = 'r3'", Long.class);
        assertEquals(7560000L, millis, "r3 修正后精确值应为 2.100 小时");
        // 区间外读数保持原值
        mockMvc.perform(get("/api/equipment/eq-act/readings"))
                .andExpect(jsonPath("$[0].readingId").value("r0"))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(0));

        // 保养状态基于修正后读数：不会出现读数已修正而状态仍基于旧值
        mockMvc.perform(get("/api/equipment/eq-act/status"))
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.runMinutes").value(198))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 恰好一个 maintenanceSnapshotVersion
        mockMvc.perform(get("/api/equipment/eq-act/maintenance-snapshots"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].snapshotVersion").value(1))
                .andExpect(jsonPath("$[0].correctionKey").value("ck-act"))
                .andExpect(jsonPath("$[0].status").value("DUE"))
                .andExpect(jsonPath("$[0].runMillis").value(11880000))
                .andExpect(jsonPath("$[0].latestCumulativeMillis").value(11880000))
                .andExpect(jsonPath("$[0].anchorCumulativeMillis").value(0))
                .andExpect(jsonPath("$[0].nextThresholdMillis").value(11400000));

        // 证据查询：单头与详情稳定排序
        mockMvc.perform(get("/api/equipment/eq-act/drift-corrections"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].correctionKey").value("ck-act"))
                .andExpect(jsonPath("$[0].anchorCount").value(3))
                .andExpect(jsonPath("$[0].affectedCount").value(5))
                .andExpect(jsonPath("$[0].maintenanceSnapshotVersion").value(1))
                .andExpect(jsonPath("$[0].equipmentVersion").value(8))
                .andExpect(jsonPath("$[0].firstSampledAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$[0].lastSampledAt").value("2026-01-01T12:00:00Z"));
        mockMvc.perform(get("/api/equipment/eq-act/drift-corrections/ck-act"))
                .andExpect(jsonPath("$.anchors", hasSize(3)))
                .andExpect(jsonPath("$.anchors[0].readingId").value("r1"))
                .andExpect(jsonPath("$.anchors[1].readingId").value("r3"))
                .andExpect(jsonPath("$.anchors[2].readingId").value("r5"))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].readingId").value("r1"))
                .andExpect(jsonPath("$.items[1].readingId").value("r2"))
                .andExpect(jsonPath("$.items[2].readingId").value("r3"))
                .andExpect(jsonPath("$.items[3].readingId").value("r4"))
                .andExpect(jsonPath("$.items[4].readingId").value("r5"))
                .andExpect(jsonPath("$.items[2].oldCumulativeMillis").value(7200000))
                .andExpect(jsonPath("$.items[2].newCumulativeMillis").value(7560000))
                .andExpect(jsonPath("$.items[2].newRevisionNo").value(2));

        // 第二次修正：快照版本递增为 2，设备版本继续前进
        mockMvc.perform(post("/api/equipment/eq-act/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-act-2","correctionKey":"ck-act-2",
                                 "expectedVersion":8,"anchors":[%s,%s]}
                                """.formatted(anchor("r3", 2, "2.100"), anchor("r5", 2, "3.400"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maintenanceSnapshotVersion").value(2))
                .andExpect(jsonPath("$.equipmentVersion").value(9))
                .andExpect(jsonPath("$.affectedCount").value(3));
        mockMvc.perform(get("/api/equipment/eq-act/maintenance-snapshots"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].snapshotVersion").value(1))
                .andExpect(jsonPath("$[1].snapshotVersion").value(2))
                .andExpect(jsonPath("$[1].correctionKey").value("ck-act-2"));
    }

    // ---------- 锚点版本变化 → 409；失败不占键 ----------

    @Test
    void activate_anchorVersionChanged_conflictAndKeyNotOccupied() throws Exception {
        register("eq-ver2", 1000);
        addReading("eq-ver2", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-ver2", "r2", 2, "2026-01-01T11:00:00Z", 120);
        addReading("eq-ver2", "r3", 3, "2026-01-01T12:00:00Z", 180);
        // 修订 r2：修订号变为 2
        mockMvc.perform(post("/api/equipment/eq-ver2/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-r2","expectedVersion":4,"cumulativeMinutes":130}
                                """))
                .andExpect(status().isCreated());

        // 锚点 expectedVersion=1 与当前修订号 2 不一致 → 409
        mockMvc.perform(post("/api/equipment/eq-ver2/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-ver-1","correctionKey":"ck-ver","expectedVersion":5,
                                 "anchors":[%s,%s,%s]}
                                """.formatted(anchor("r1", 1, "1.000"), anchor("r2", 1, "2.000"),
                                anchor("r3", 1, "3.000"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANCHOR_VERSION_CONFLICT"));

        // 失败不占键：同 requestId 修正锚点版本后成功
        mockMvc.perform(post("/api/equipment/eq-ver2/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-ver-1","correctionKey":"ck-ver","expectedVersion":5,
                                 "anchors":[%s,%s,%s]}
                                """.formatted(anchor("r1", 1, "1.000"), anchor("r2", 2, "2.000"),
                                anchor("r3", 1, "3.000"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maintenanceSnapshotVersion").value(1));
    }

    // ---------- 冻结点：一个冻结读数落入修正集合 → 整单 422 ----------

    @Test
    void activate_frozenReadingInside_wholeOrderRejected() throws Exception {
        register("eq-frozen", 1000);
        addReading("eq-frozen", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-frozen", "r2", 2, "2026-01-01T11:00:00Z", 120);
        addReading("eq-frozen", "r3", 3, "2026-01-01T12:00:00Z", 180);
        // 完成保养：r2 被冻结为依据
        mockMvc.perform(post("/api/equipment/eq-frozen/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-fr","expectedVersion":4,"readingId":"r2",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        String body = """
                {"correctionKey":"ck-fr","anchors":[%s,%s]}
                """.formatted(anchor("r1", 1, "1.000"), anchor("r3", 1, "3.500"));
        // 预览同样拒绝
        mockMvc.perform(post("/api/equipment/eq-frozen/drift-corrections/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRECTION_FROZEN_READING"));
        // 激活整单 422：不能只修其余读数
        mockMvc.perform(post("/api/equipment/eq-frozen/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-fr-1","correctionKey":"ck-fr","expectedVersion":5,
                                 "anchors":[%s,%s]}
                                """.formatted(anchor("r1", 1, "1.000"), anchor("r3", 1, "3.500"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRECTION_FROZEN_READING"));

        // 整单回滚：无任何读数被修正、无修正单、无快照、版本未前进
        mockMvc.perform(get("/api/equipment/eq-frozen/readings"))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[1].revisionNo").value(1))
                .andExpect(jsonPath("$[2].revisionNo").value(1));
        mockMvc.perform(get("/api/equipment/eq-frozen/drift-corrections"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-frozen/maintenance-snapshots"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-frozen/status"))
                .andExpect(jsonPath("$.version").value(5));
        Integer idem = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'dc-fr-1'",
                Integer.class);
        assertEquals(0, idem, "失败的激活不得占用幂等键");
    }

    // ---------- 单调边界：区间外相邻读数冲突 → 422；边界相等允许 ----------

    @Test
    void activate_boundaryConflicts() throws Exception {
        // 首锚点修正值低于区间外前相邻读数 → 422
        register("eq-b1", 1000);
        addReading("eq-b1", "r0", 1, "2026-01-01T09:00:00Z", 50);
        addReading("eq-b1", "r1", 2, "2026-01-01T10:00:00Z", 60);
        addReading("eq-b1", "r2", 3, "2026-01-01T11:00:00Z", 120);
        MvcResult low = activate("eq-b1", "dc-b1", "ck-b1", 4,
                anchor("r1", 1, "0.500"), anchor("r2", 1, "2.000"));
        assertEquals(422, low.getResponse().getStatus());
        assertEquals("CORRECTION_BOUNDARY_CONFLICT",
                com.jayway.jsonpath.JsonPath.read(low.getResponse().getContentAsString(), "$.code"));

        // 尾锚点修正值高于区间外后相邻读数 → 422
        register("eq-b2", 1000);
        addReading("eq-b2", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-b2", "r2", 2, "2026-01-01T11:00:00Z", 120);
        addReading("eq-b2", "r3", 3, "2026-01-01T12:00:00Z", 180);
        MvcResult high = activate("eq-b2", "dc-b2", "ck-b2", 4,
                anchor("r1", 1, "1.000"), anchor("r2", 1, "5.000"));
        assertEquals(422, high.getResponse().getStatus());

        // 边界相等允许（单调不减）：首锚点 1.000h = 60min 与前相邻读数相等
        register("eq-b3", 1000);
        addReading("eq-b3", "r0", 1, "2026-01-01T09:00:00Z", 60);
        addReading("eq-b3", "r1", 2, "2026-01-01T10:00:00Z", 60);
        addReading("eq-b3", "r2", 3, "2026-01-01T11:00:00Z", 120);
        MvcResult equal = activate("eq-b3", "dc-b3", "ck-b3", 4,
                anchor("r1", 1, "1.000"), anchor("r2", 1, "2.000"));
        assertEquals(201, equal.getResponse().getStatus());
    }

    // ---------- 锚点校准值必须严格递增 ----------

    @Test
    void activate_anchorsNotStrictlyIncreasing_rejected() throws Exception {
        register("eq-inc", 1000);
        addReading("eq-inc", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-inc", "r2", 2, "2026-01-01T11:00:00Z", 120);
        addReading("eq-inc", "r3", 3, "2026-01-01T12:00:00Z", 180);
        // 相等不允许
        MvcResult equal = activate("eq-inc", "dc-i1", "ck-i1", 4,
                anchor("r1", 1, "2.000"), anchor("r2", 1, "2.000"));
        assertEquals(422, equal.getResponse().getStatus());
        // 递减不允许
        MvcResult decreasing = activate("eq-inc", "dc-i2", "ck-i2", 4,
                anchor("r1", 1, "3.000"), anchor("r2", 1, "2.000"));
        assertEquals(422, decreasing.getResponse().getStatus());
        assertEquals("ANCHOR_VALUES_NOT_INCREASING", com.jayway.jsonpath.JsonPath
                .read(decreasing.getResponse().getContentAsString(), "$.code"));
    }

    // ---------- 锚点读数遗漏 → 404；重复锚点 → 422 ----------

    @Test
    void activate_missingOrDuplicateAnchor_rejected() throws Exception {
        register("eq-miss", 1000);
        addReading("eq-miss", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-miss", "r2", 2, "2026-01-01T11:00:00Z", 120);
        MvcResult missing = activate("eq-miss", "dc-m1", "ck-m1", 3,
                anchor("r1", 1, "1.000"), anchor("nope", 1, "2.000"));
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("READING_NOT_FOUND", com.jayway.jsonpath.JsonPath
                .read(missing.getResponse().getContentAsString(), "$.code"));

        MvcResult duplicate = activate("eq-miss", "dc-m2", "ck-m2", 3,
                anchor("r1", 1, "1.000"), anchor("r1", 1, "1.500"));
        assertEquals(422, duplicate.getResponse().getStatus());
        assertEquals("ANCHOR_DUPLICATE", com.jayway.jsonpath.JsonPath
                .read(duplicate.getResponse().getContentAsString(), "$.code"));
    }

    // ---------- 幂等：同参（锚点换序）重放首次快照，异参 409，correctionKey 唯一 ----------

    @Test
    void idempotency_replayNormalizedMismatchAndUniqueKey() throws Exception {
        register("eq-idem2", 190);
        addReading("eq-idem2", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-idem2", "r2", 2, "2026-01-01T10:30:00Z", 90);
        addReading("eq-idem2", "r3", 3, "2026-01-01T11:00:00Z", 120);
        addReading("eq-idem2", "r4", 4, "2026-01-01T11:30:00Z", 150);
        addReading("eq-idem2", "r5", 5, "2026-01-01T12:00:00Z", 180);

        MvcResult first = activate("eq-idem2", "dc-idem", "ck-idem", 6,
                anchor("r1", 1, "1.000"), anchor("r3", 1, "2.100"), anchor("r5", 1, "3.300"));
        assertEquals(201, first.getResponse().getStatus());

        // 同 requestId + 锚点换序：按时间规范化后等价 → 重放首次快照（响应逐字节相同）
        MvcResult replay = activate("eq-idem2", "dc-idem", "ck-idem", 6,
                anchor("r5", 1, "3.300"), anchor("r1", 1, "1.000"), anchor("r3", 1, "2.100"));
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 业务效果只发生一次
        mockMvc.perform(get("/api/equipment/eq-idem2/drift-corrections"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-idem2/maintenance-snapshots"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-idem2/status"))
                .andExpect(jsonPath("$.version").value(7));

        // 同 requestId 异参 → 409
        MvcResult mismatch = activate("eq-idem2", "dc-idem", "ck-idem", 6,
                anchor("r1", 1, "1.000"), anchor("r3", 1, "2.100"), anchor("r5", 1, "3.301"));
        assertEquals(409, mismatch.getResponse().getStatus());
        assertEquals("REQUEST_ID_CONFLICT", com.jayway.jsonpath.JsonPath
                .read(mismatch.getResponse().getContentAsString(), "$.code"));

        // correctionKey 唯一：不同 requestId 复用同键 → 409
        MvcResult dupKey = activate("eq-idem2", "dc-idem-other", "ck-idem", 7,
                anchor("r1", 2, "1.000"), anchor("r5", 2, "3.300"));
        assertEquals(409, dupKey.getResponse().getStatus());
        assertEquals("CORRECTION_KEY_EXISTS", com.jayway.jsonpath.JsonPath
                .read(dupKey.getResponse().getContentAsString(), "$.code"));

        // 设备版本冲突失败不占键：同 requestId 修正版本后成功
        MvcResult badVersion = activate("eq-idem2", "dc-idem-v", "ck-idem-v", 99,
                anchor("r1", 2, "1.000"), anchor("r5", 2, "3.300"));
        assertEquals(409, badVersion.getResponse().getStatus());
        MvcResult retry = activate("eq-idem2", "dc-idem-v", "ck-idem-v", 7,
                anchor("r1", 2, "1.000"), anchor("r5", 2, "3.300"));
        assertEquals(201, retry.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-idem2/maintenance-snapshots"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].snapshotVersion").value(2));
    }

    // ---------- 参数校验 → 400 ----------

    @Test
    void validation_badRequests() throws Exception {
        register("eq-val", 1000);
        addReading("eq-val", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-val", "r2", 2, "2026-01-01T11:00:00Z", 120);

        // 锚点少于 2 个
        MvcResult tooFew = activate("eq-val", "dc-v1", "ck-v1", 3, anchor("r1", 1, "1.000"));
        assertEquals(400, tooFew.getResponse().getStatus());

        // 锚点超过 20 个
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append(anchor("r" + i, 1, "1.000"));
        }
        MvcResult tooMany = postJson("/api/equipment/eq-val/drift-corrections", """
                {"requestId":"dc-v2","correctionKey":"ck-v2","expectedVersion":3,
                 "anchors":[%s]}
                """.formatted(many));
        assertEquals(400, tooMany.getResponse().getStatus());

        // 校准值超过 3 位小数
        MvcResult precision = postJson("/api/equipment/eq-val/drift-corrections", """
                {"requestId":"dc-v3","correctionKey":"ck-v3","expectedVersion":3,
                 "anchors":[{"readingId":"r1","expectedVersion":1,"cumulativeHours":1.000},
                            {"readingId":"r2","expectedVersion":1,"cumulativeHours":1.2345}]}
                """);
        assertEquals(400, precision.getResponse().getStatus());

        // 校准值为负
        MvcResult negative = postJson("/api/equipment/eq-val/drift-corrections", """
                {"requestId":"dc-v4","correctionKey":"ck-v4","expectedVersion":3,
                 "anchors":[{"readingId":"r1","expectedVersion":1,"cumulativeHours":-1.000},
                            {"readingId":"r2","expectedVersion":1,"cumulativeHours":2.000}]}
                """);
        assertEquals(400, negative.getResponse().getStatus());

        // requestId 必填
        MvcResult noRequestId = postJson("/api/equipment/eq-val/drift-corrections", """
                {"correctionKey":"ck-v5","expectedVersion":3,
                 "anchors":[{"readingId":"r1","expectedVersion":1,"cumulativeHours":1.000},
                            {"readingId":"r2","expectedVersion":1,"cumulativeHours":2.000}]}
                """);
        assertEquals(400, noRequestId.getResponse().getStatus());
    }

    // ---------- 亚分钟精度：毫秒为规范值，分钟视图四舍五入 ----------

    @Test
    void activate_subMinutePrecision_canonicalMillis() throws Exception {
        register("eq-sub", 10000);
        addReading("eq-sub", "r1", 1, "2026-01-01T10:00:00Z", 60);
        addReading("eq-sub", "rmid", 2, "2026-01-01T10:30:00Z", 90);
        addReading("eq-sub", "r2", 3, "2026-01-01T11:00:00Z", 120);

        mockMvc.perform(post("/api/equipment/eq-sub/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-sub","correctionKey":"ck-sub","expectedVersion":4,
                                 "anchors":[%s,%s]}
                                """.formatted(anchor("r1", 1, "1.000"), anchor("r2", 1, "2.002"))))
                .andExpect(status().isCreated())
                // 中点插值：(1.000 + 2.002) / 2 = 1.501h = 90.06min
                .andExpect(jsonPath("$.items[1].readingId").value("rmid"))
                .andExpect(jsonPath("$.items[1].newCumulativeHours").value("1.501"))
                .andExpect(jsonPath("$.items[1].newCumulativeMillis").value(5403600))
                .andExpect(jsonPath("$.items[2].newCumulativeHours").value("2.002"))
                .andExpect(jsonPath("$.items[2].newCumulativeMillis").value(7207200));

        // 分钟视图四舍五入：120.12min → 120；毫秒列保留精确值
        mockMvc.perform(get("/api/equipment/eq-sub/readings"))
                .andExpect(jsonPath("$[2].readingId").value("r2"))
                .andExpect(jsonPath("$[2].cumulativeMinutes").value(120))
                .andExpect(jsonPath("$[2].revisionNo").value(2));
        Long millis = jdbc.queryForObject(
                "SELECT cumulative_millis FROM reading WHERE equipment_id = 'eq-sub'"
                        + " AND reading_id = 'r2'", Long.class);
        assertEquals(7207200L, millis);
        Long revisionMillis = jdbc.queryForObject(
                "SELECT cumulative_millis FROM reading_revision WHERE equipment_id = 'eq-sub'"
                        + " AND reading_id = 'r2' AND revision_no = 2", Long.class);
        assertEquals(7207200L, revisionMillis);
    }

    // ---------- 证据查询：不存在分支 ----------

    @Test
    void evidence_notFoundBranches() throws Exception {
        mockMvc.perform(get("/api/equipment/nope/drift-corrections"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope/maintenance-snapshots"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/equipment/nope/drift-corrections/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"correctionKey":"ck-x","anchors":[%s,%s]}
                                """.formatted(anchor("r1", 1, "1.000"), anchor("r2", 1, "2.000"))))
                .andExpect(status().isNotFound());

        register("eq-ev", 1000);
        mockMvc.perform(get("/api/equipment/eq-ev/drift-corrections/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/eq-ev/drift-corrections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-ev/maintenance-snapshots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
