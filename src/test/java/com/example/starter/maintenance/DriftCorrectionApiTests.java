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
 * 时钟漂移修正 API 的真实 H2 数据库测试：预览/激活/证据主流程、单调边界、
 * 冻结点、整体重算、幂等与 correctionKey 唯一约束。
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
        jdbc.update("DELETE FROM maintenance_item_snapshot");
        jdbc.update("DELETE FROM maintenance_snapshot");
        jdbc.update("DELETE FROM drift_correction_reading");
        jdbc.update("DELETE FROM drift_correction_anchor");
        jdbc.update("DELETE FROM drift_correction");
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
                .andExpect(status().isCreated());
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

    // 锚点 r1=2.000h、r5=6.000h 的标准修正单（10:00~12:00，每 30 分钟一条读数）
    private String standardBody(String requestId, String correctionKey, long expectedVersion) {
        return """
                {"requestId":"%s","correctionKey":"%s","expectedVersion":%d,"anchors":[
                  {"readingId":"r1","expectedVersion":1,"calibratedHours":2.000},
                  {"readingId":"r5","expectedVersion":1,"calibratedHours":6.000}
                ]}
                """.formatted(requestId, correctionKey, expectedVersion);
    }

    private void seedStandardEquipment() throws Exception {
        register("eq-drift", 100);
        addReading("eq-drift", "add-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-drift", "add-r2", 2, "r2", "2026-01-01T10:30:00Z", 200);
        addReading("eq-drift", "add-r3", 3, "r3", "2026-01-01T11:00:00Z", 300);
        addReading("eq-drift", "add-r4", 4, "r4", "2026-01-01T11:30:00Z", 400);
        addReading("eq-drift", "add-r5", 5, "r5", "2026-01-01T12:00:00Z", 500);
    }

    // ---------- 预览 ----------

    @Test
    void preview_returnsOldNewSegmentAndItems_withoutWriting() throws Exception {
        seedStandardEquipment();

        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(standardBody("prev-1", "ck-prev", 6)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(true))
                .andExpect(jsonPath("$.rejectionCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.intervalStart").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.intervalEnd").value("2026-01-01T12:00:00Z"))
                // 锚点规范化按时刻升序
                .andExpect(jsonPath("$.anchors", hasSize(2)))
                .andExpect(jsonPath("$.anchors[0].positionNo").value(1))
                .andExpect(jsonPath("$.anchors[0].readingId").value("r1"))
                // 区间内 5 条读数全部返回，稳定排序
                .andExpect(jsonPath("$.readings", hasSize(5)))
                .andExpect(jsonPath("$.readings[0].readingId").value("r1"))
                .andExpect(jsonPath("$.readings[0].anchor").value(true))
                .andExpect(jsonPath("$.readings[0].oldHours").value(1.666667))
                .andExpect(jsonPath("$.readings[0].newHours").value(2.000))
                .andExpect(jsonPath("$.readings[1].readingId").value("r2"))
                .andExpect(jsonPath("$.readings[1].segmentIndex").value(1))
                .andExpect(jsonPath("$.readings[1].newHours").value(3.000))
                .andExpect(jsonPath("$.readings[2].readingId").value("r3"))
                .andExpect(jsonPath("$.readings[2].newHours").value(4.000))
                .andExpect(jsonPath("$.readings[3].readingId").value("r4"))
                .andExpect(jsonPath("$.readings[3].newHours").value(5.000))
                .andExpect(jsonPath("$.readings[4].readingId").value("r5"))
                .andExpect(jsonPath("$.readings[4].anchor").value(true))
                .andExpect(jsonPath("$.readings[4].newHours").value(6.000))
                // 受影响保养项目拟重算：最新 6.0 小时 >= 周期 1.666667 小时 => DUE
                .andExpect(jsonPath("$.maintenanceItems", hasSize(1)))
                .andExpect(jsonPath("$.maintenanceItems[0].itemKey").value("PERIODIC"))
                .andExpect(jsonPath("$.maintenanceItems[0].status").value("DUE"))
                .andExpect(jsonPath("$.maintenanceItems[0].runHours").value(6.000000))
                .andExpect(jsonPath("$.maintenanceItems[0].nextThresholdHours").value(1.666667));

        // 预览不写数据：读数仍为旧值、设备版本不变、无修正单、无快照
        mockMvc.perform(get("/api/equipment/eq-drift/readings"))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(100));
        mockMvc.perform(get("/api/equipment/eq-drift/status"))
                .andExpect(jsonPath("$.version").value(6));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM drift_correction", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_snapshot", Integer.class));
    }

    @Test
    void preview_staleVersion_markedNotActivatable() throws Exception {
        seedStandardEquipment();
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(standardBody("prev-stale", "ck-stale", 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(false))
                .andExpect(jsonPath("$.rejectionCode").value("VERSION_CONFLICT"));
    }

    // ---------- 激活主流程与整体重算 ----------

    @Test
    void activate_writesNewVersionsAndSingleSnapshotAtomically() throws Exception {
        seedStandardEquipment();

        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(standardBody("act-1", "ck-1", 6)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctionKey").value("ck-1"))
                .andExpect(jsonPath("$.equipmentVersion").value(7))
                .andExpect(jsonPath("$.maintenanceSnapshotVersion").value(1))
                .andExpect(jsonPath("$.readings", hasSize(5)))
                .andExpect(jsonPath("$.maintenanceItems[0].status").value("DUE"));

        // 每条受影响读数生成新版本，旧版本不可变
        mockMvc.perform(get("/api/equipment/eq-drift/readings"))
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(120))
                .andExpect(jsonPath("$[0].revisionNo").value(2))
                .andExpect(jsonPath("$[2].cumulativeMinutes").value(240))
                .andExpect(jsonPath("$[4].cumulativeMinutes").value(360));
        mockMvc.perform(get("/api/equipment/eq-drift/readings/r3/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(300))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(240));
        String changeType = jdbc.queryForObject(
                "SELECT change_type FROM reading_revision WHERE equipment_id='eq-drift'"
                        + " AND reading_id='r3' AND revision_no=2",
                String.class);
        org.junit.jupiter.api.Assertions.assertEquals("DRIFT_CORRECTION", changeType);

        // 只生成一个 maintenanceSnapshotVersion
        Integer snapshotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_snapshot WHERE equipment_id='eq-drift'", Integer.class);
        assertEquals(1, snapshotCount);
        Integer itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_item_snapshot s JOIN maintenance_snapshot m"
                        + " ON s.snapshot_id = m.snapshot_id WHERE m.equipment_id='eq-drift'",
                Integer.class);
        assertEquals(1, itemCount);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT maintenance_snapshot_version FROM equipment WHERE equipment_id='eq-drift'",
                Long.class));
        mockMvc.perform(get("/api/equipment/eq-drift/status"))
                .andExpect(jsonPath("$.version").value(7))
                // 保养状态基于修正后的当前工时（360 分钟）
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(360))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 证据查询：只读、稳定排序
        mockMvc.perform(get("/api/equipment/eq-drift/drift-corrections/ck-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenanceSnapshotVersion").value(1))
                .andExpect(jsonPath("$.anchors", hasSize(2)))
                .andExpect(jsonPath("$.anchors[1].readingId").value("r5"))
                .andExpect(jsonPath("$.anchors[1].calibratedHours").value(6.000))
                .andExpect(jsonPath("$.readings", hasSize(5)))
                .andExpect(jsonPath("$.readings[0].readingId").value("r1"))
                .andExpect(jsonPath("$.readings[2].readingId").value("r3"))
                .andExpect(jsonPath("$.readings[2].oldRevisionNo").value(1))
                .andExpect(jsonPath("$.readings[2].newRevisionNo").value(2))
                // 证据包含本次一次性重算的全部保养项目（与读数修正同事务、同快照版本）
                .andExpect(jsonPath("$.maintenanceItems", hasSize(1)))
                .andExpect(jsonPath("$.maintenanceItems[0].itemKey").value("PERIODIC"))
                .andExpect(jsonPath("$.maintenanceItems[0].status").value("DUE"))
                .andExpect(jsonPath("$.maintenanceItems[0].runHours").value(6.000000));
    }

    @Test
    void activate_recalculatesNotDueAgainstFrozenMaintenanceAnchor() throws Exception {
        // 设备周期 300 分钟（5 小时）。先完成一次保养锚定 r1（100 分钟=1.666667 小时，冻结），
        // 再修正区间 r2..r5 到 2.000..5.000 小时；区间外 r1 保持原值，锚点快照不变。
        // 最新工时 5.000 - 锚点 1.666667 = 3.333333 小时 < 5 小时 => NOT_DUE，
        // 下一阈值 = 1.666667 + 5 = 6.666667。
        register("eq-drift", 300);
        addReading("eq-drift", "add-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-drift", "add-r2", 2, "r2", "2026-01-01T10:30:00Z", 200);
        addReading("eq-drift", "add-r3", 3, "r3", "2026-01-01T11:00:00Z", 300);
        addReading("eq-drift", "add-r4", 4, "r4", "2026-01-01T11:30:00Z", 400);
        addReading("eq-drift", "add-r5", 5, "r5", "2026-01-01T12:00:00Z", 500);
        mockMvc.perform(post("/api/equipment/eq-drift/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-anchor","expectedVersion":6,"readingId":"r1",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"act-anchor","correctionKey":"ck-anchor","expectedVersion":7,
                                 "anchors":[
                                   {"readingId":"r2","expectedVersion":1,"calibratedHours":2.000},
                                   {"readingId":"r5","expectedVersion":1,"calibratedHours":5.000}
                                 ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maintenanceItems[0].status").value("NOT_DUE"))
                .andExpect(jsonPath("$.maintenanceItems[0].lastAnchorReadingId").value("r1"))
                // 最近保养锚点工时快照不变：100 分钟 = 1.666667 小时
                .andExpect(jsonPath("$.maintenanceItems[0].lastAnchorCumulativeHours").value(1.666667))
                .andExpect(jsonPath("$.maintenanceItems[0].runHours").value(3.333333))
                .andExpect(jsonPath("$.maintenanceItems[0].nextThresholdHours").value(6.666667));

        // 区间外左邻 r1 被保养冻结但不在修正集合内：保持原值 100 分钟，序列仍单调
        mockMvc.perform(get("/api/equipment/eq-drift/readings"))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(100))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].anchored").value(true))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(120))
                .andExpect(jsonPath("$[4].cumulativeMinutes").value(300));
    }

    // ---------- 失败分支 ----------

    @Test
    void activate_versionConflict_409AndNoWrite() throws Exception {
        seedStandardEquipment();
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(standardBody("act-badver", "ck-badver", 5)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(get("/api/equipment/eq-drift/status"))
                .andExpect(jsonPath("$.version").value(6));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM drift_correction", Integer.class));
    }

    @Test
    void activate_frozenReadingWholeOrder_422AndNothingChanged() throws Exception {
        seedStandardEquipment();
        // 完成保养锚定 r3（冻结）
        mockMvc.perform(post("/api/equipment/eq-drift/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-freeze","expectedVersion":6,"readingId":"r3",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // r3 落在修正集合内：整单 422，不能只修其余读数
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(standardBody("act-frozen", "ck-frozen", 7)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRECTION_FROZEN_READING"));

        // 无任何读数被修正、无快照、版本不变
        mockMvc.perform(get("/api/equipment/eq-drift/readings"))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(200))
                .andExpect(jsonPath("$[2].cumulativeMinutes").value(300))
                .andExpect(jsonPath("$[2].revisionNo").value(1));
        mockMvc.perform(get("/api/equipment/eq-drift/status"))
                .andExpect(jsonPath("$.version").value(7));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_snapshot", Integer.class));

        // 预览同样标记冻结点
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(standardBody("prev-frozen", "ck-frozen-prev", 7)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activatable").value(false))
                .andExpect(jsonPath("$.rejectionCode").value("CORRECTION_FROZEN_READING"))
                .andExpect(jsonPath("$.readings[2].frozen").value(true));
    }

    @Test
    void activate_boundaryConflictWithOutsideNeighbor_422() throws Exception {
        seedStandardEquipment();
        // 修正区间 r2..r4，首值 0.500h 低于区间外左邻 r1（1.666667h）
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"act-bound","correctionKey":"ck-bound","expectedVersion":6,
                                 "anchors":[
                                   {"readingId":"r2","expectedVersion":1,"calibratedHours":0.500},
                                   {"readingId":"r4","expectedVersion":1,"calibratedHours":1.000}
                                 ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRECTION_BOUNDARY_CONFLICT"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM drift_correction", Integer.class));
    }

    @Test
    void activate_nonIncreasingAnchors_422() throws Exception {
        seedStandardEquipment();
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"act-incr","correctionKey":"ck-incr","expectedVersion":6,
                                 "anchors":[
                                   {"readingId":"r1","expectedVersion":1,"calibratedHours":3.000},
                                   {"readingId":"r5","expectedVersion":1,"calibratedHours":3.000}
                                 ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRECTION_ANCHOR_NOT_STRICT_INCREASING"));
    }

    @Test
    void activate_anchorRevisionChanged_409() throws Exception {
        seedStandardEquipment();
        // 先成功修正一次，r2 当前修订号变为 2
        postJson("/api/equipment/eq-drift/drift-corrections",
                standardBody("act-first", "ck-first", 6));
        // 第二单锚点 r2 仍声明期望修订号 1 → 版本变化，整单 409
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"act-stale-rev","correctionKey":"ck-stale-rev",
                                 "expectedVersion":7,
                                 "anchors":[
                                   {"readingId":"r2","expectedVersion":1,"calibratedHours":3.500},
                                   {"readingId":"r5","expectedVersion":2,"calibratedHours":7.000}
                                 ]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANCHOR_REVISION_CONFLICT"));
        // 失败后读数保持第一次修正的结果，快照仍只有 1 个
        mockMvc.perform(get("/api/equipment/eq-drift/readings/r2/revisions"))
                .andExpect(jsonPath("$.length()").value(2));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_snapshot WHERE equipment_id='eq-drift'", Integer.class));
    }

    @Test
    void activate_missingAnchorReading_422() throws Exception {
        seedStandardEquipment();
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"act-miss","correctionKey":"ck-miss","expectedVersion":6,
                                 "anchors":[
                                   {"readingId":"r1","expectedVersion":1,"calibratedHours":2.000},
                                   {"readingId":"rX","expectedVersion":1,"calibratedHours":6.000}
                                 ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORRECTION_ANCHOR_READING_NOT_FOUND"));
    }

    @Test
    void anchorCountOutOfRange_400() throws Exception {
        seedStandardEquipment();
        // 仅 1 个锚点：违反 2~20 约束
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"act-one","correctionKey":"ck-one","expectedVersion":6,
                                 "anchors":[
                                   {"readingId":"r1","expectedVersion":1,"calibratedHours":2.000}
                                 ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 幂等与唯一键 ----------

    @Test
    void idempotency_replayReorderMismatchFailureAndUniqueKey() throws Exception {
        seedStandardEquipment();

        MvcResult first = postJson("/api/equipment/eq-drift/drift-corrections",
                standardBody("idem-act", "ck-idem", 6));
        assertEquals(201, first.getResponse().getStatus());

        // 同键同参重放：原成功结果，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-drift/drift-corrections",
                standardBody("idem-act", "ck-idem", 6));
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-drift/readings/r1/revisions"))
                .andExpect(jsonPath("$.length()").value(2));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM drift_correction WHERE correction_id='ck-idem'", Integer.class));

        // 锚点换序提交：与顺序提交等价，同 requestId 仍重放首次快照
        MvcResult reordered = postJson("/api/equipment/eq-drift/drift-corrections", """
                {"requestId":"idem-act","correctionKey":"ck-idem","expectedVersion":6,"anchors":[
                  {"readingId":"r5","expectedVersion":1,"calibratedHours":6.000},
                  {"readingId":"r1","expectedVersion":1,"calibratedHours":2.000}
                ]}
                """);
        assertEquals(201, reordered.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                reordered.getResponse().getContentAsString());

        // 同键异参：409
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(standardBody("idem-act", "ck-idem", 7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // correctionKey 全局唯一：不同 requestId 复用已占用的 correctionKey → 409
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(standardBody("idem-other", "ck-idem", 7)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRECTION_KEY_EXISTS"));

        // 失败不占键：422 后同一 requestId 以合法参数（新 correctionKey）可成功
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-retry","correctionKey":"ck-retry-bad",
                                 "expectedVersion":7,
                                 "anchors":[
                                   {"readingId":"r1","expectedVersion":2,"calibratedHours":3.000},
                                   {"readingId":"r5","expectedVersion":2,"calibratedHours":3.000}
                                 ]}
                                """))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/equipment/eq-drift/drift-corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-retry","correctionKey":"ck-retry-ok",
                                 "expectedVersion":7,
                                 "anchors":[
                                   {"readingId":"r1","expectedVersion":2,"calibratedHours":3.000},
                                   {"readingId":"r5","expectedVersion":2,"calibratedHours":7.000}
                                 ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maintenanceSnapshotVersion").value(2));
    }

    @Test
    void evidence_notFoundAndWrongEquipment() throws Exception {
        seedStandardEquipment();
        mockMvc.perform(get("/api/equipment/eq-drift/drift-corrections/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope/drift-corrections/x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
    }
}
