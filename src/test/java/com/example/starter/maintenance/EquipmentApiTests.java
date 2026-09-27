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
 * 设备工时保养 API 主流程与失败分支测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance_correction");
        jdbc.update("DELETE FROM maintenance_confirmation");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM maintenance_cycle");
        jdbc.update("DELETE FROM evidence_material_summary");
        jdbc.update("DELETE FROM evidence_check_result");
        jdbc.update("DELETE FROM evidence_package");
        jdbc.update("DELETE FROM maintenance_template_item");
        jdbc.update("DELETE FROM maintenance_template");
        jdbc.update("DELETE FROM material");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void register(String equipmentId, long periodMinutes) throws Exception {
        registerWithThreshold(equipmentId, periodMinutes, null);
    }

    private void registerWithThreshold(String equipmentId, long periodMinutes, Long threshold)
            throws Exception {
        String thresholdJson = threshold == null ? "null" : threshold.toString();
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d,
                                 "overdueThresholdMinutes":%s}
                                """.formatted(equipmentId, equipmentId, periodMinutes, thresholdJson)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
    }

    /** 建立含一个必检项 c1 的模板版本 1，返回当前设备版本。 */
    private long createTemplateWithC1(String equipmentId, long expectedVersion) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/template/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tpl-%s","expectedVersion":%d,"items":[
                                  {"itemCode":"c1","itemName":"检查项1","required":true}]}
                                """.formatted(equipmentId, expectedVersion)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        return expectedVersion + 1;
    }

    private void registerMaterial(String materialId, String name, String unit) throws Exception {
        mockMvc.perform(post("/api/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"matreg-%s","materialId":"%s","materialName":"%s","unit":"%s"}
                                """.formatted(materialId, materialId, name, unit)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /** 提交 c1=PASS 与 1 个材料的证据包。 */
    private long submitPassEvidence(String equipmentId, String requestId, long expectedVersion,
                                    String submittedBy, String materialId, double quantity)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment/" + equipmentId + "/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"submittedBy":"%s",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null}],
                                 "materials":[{"materialId":"%s","quantity":%s}]}
                                """.formatted(requestId, expectedVersion, submittedBy, materialId,
                                trimDouble(quantity))))
                .andExpect(status().isCreated())
                .andReturn();
        return expectedVersion + 1;
    }

    private static String trimDouble(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
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

    // ---------- 主流程：登记 → 读数 → DUE → 保养 → OK → 历史 ----------

    @Test
    void mainFlow_statusTransitionsAndHistory() throws Exception {
        register("eq-main", 100);

        // 初始状态：累计工时 0，OK
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        addReading("eq-main", "add-r1", 1, "r1", "2026-01-01T10:00:00Z", 50, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(50))
                .andExpect(jsonPath("$.status").value("OK"));

        // 达到保养周期 → DUE
        addReading("eq-main", "add-r2", 2, "r2", "2026-01-01T11:00:00Z", 130, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(130))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 完工前门禁诊断：无模板、无证据包、无材料摘要
        mockMvc.perform(get("/api/equipment/eq-main/cycles/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.readiness.readyToComplete").value(false))
                .andExpect(jsonPath("$.readiness.materialSummaryCount").value(0))
                .andExpect(jsonPath("$.readiness.equipmentStatus").value("DUE"));

        // 建立模板版本 1（必检项 c1）、登记材料、提交证据包版本 1
        createTemplateWithC1("eq-main", 3);
        registerMaterial("oil-1", "润滑油", "L");
        submitPassEvidence("eq-main", "ev-1", 4, "bob", "oil-1", 2.5);

        // 另一维护角色确认证据包版本 1，原子完成保养
        mockMvc.perform(post("/api/equipment/eq-main/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-1","expectedVersion":5,"readingId":"r2","anchorRevisionNo":1,
                                 "expectedPackageVersion":1,"confirmedBy":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorSampledAt").value("2026-01-01T11:00:00Z"))
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(130))
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.evidencePackageVersion").value(1))
                .andExpect(jsonPath("$.confirmedBy").value("alice"))
                .andExpect(jsonPath("$.equipmentVersion").value(6));

        // 保养后本轮运行分钟从锚点重算，周期推进到 2
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.cycleNo").value(2))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(130))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
        mockMvc.perform(get("/api/equipment/eq-main/cycles"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].maintenanceId").isNumber())
                .andExpect(jsonPath("$[1].status").value("OPEN"))
                .andExpect(jsonPath("$[1].cycleNo").value(2))
                .andExpect(jsonPath("$[1].startAnchorCumulativeMinutes").value(130));

        addReading("eq-main", "add-r3", 6, "r3", "2026-01-01T12:00:00Z", 180, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(50))
                .andExpect(jsonPath("$.status").value("OK"));

        // 读数历史：按采样时刻升序，r2 已锚定
        mockMvc.perform(get("/api/equipment/eq-main/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].anchored").value(true))
                .andExpect(jsonPath("$[2].readingId").value("r3"));

        // 保养历史：锚点快照保留
        mockMvc.perform(get("/api/equipment/eq-main/maintenances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].readingId").value("r2"))
                .andExpect(jsonPath("$[0].anchorRevisionNo").value(1))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(130));

        // 修订历史：初始登记为修订 1
        mockMvc.perform(get("/api/equipment/eq-main/readings/r2/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(130));
    }

    // ---------- 设备登记 ----------

    @Test
    void register_duplicateEquipment_conflict() throws Exception {
        register("eq-dup", 100);
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-dup-2","equipmentId":"eq-dup","maintenancePeriodMinutes":200}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_EXISTS"));
    }

    @Test
    void register_invalidParams_badRequest() throws Exception {
        // 周期必须为正整数
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-bad-1","equipmentId":"eq-bad","maintenancePeriodMinutes":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // requestId 必填
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"equipmentId":"eq-bad","maintenancePeriodMinutes":100}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ---------- 版本校验 ----------

    @Test
    void write_versionConflict_returns409() throws Exception {
        register("eq-ver", 100);
        addReading("eq-ver", "add-v1", 99, "r1", "2026-01-01T10:00:00Z", 10, 409);
        // 失败的写操作不改变版本
        mockMvc.perform(get("/api/equipment/eq-ver/status"))
                .andExpect(jsonPath("$.version").value(1));
        addReading("eq-ver", "add-v2", 1, "r1", "2026-01-01T10:00:00Z", 10, 201);
        // 修订同样校验版本
        mockMvc.perform(post("/api/equipment/eq-ver/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-v1","expectedVersion":1,"cumulativeMinutes":20}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    // ---------- 单调性与补录 ----------

    @Test
    void addReading_backfillMustSatisfyBothNeighbors() throws Exception {
        register("eq-mono", 1000);
        addReading("eq-mono", "m1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-mono", "m2", 2, "r3", "2026-01-01T12:00:00Z", 300, 201);

        // 合法补录：100 <= 200 <= 300
        addReading("eq-mono", "m3", 3, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 补录超过后邻（300）：即使不小于前邻也必须 422，不得仅与最新值比较
        addReading("eq-mono", "m4", 4, "r2x", "2026-01-01T11:30:00Z", 350, 422);
        // 补录低于前邻（100）
        addReading("eq-mono", "m5", 4, "r2y", "2026-01-01T10:30:00Z", 50, 422);
        // 末尾追加低于最新值
        addReading("eq-mono", "m6", 4, "r4", "2026-01-01T13:00:00Z", 250, 422);
        // 边界相等允许（单调不减）
        addReading("eq-mono", "m7", 4, "r4", "2026-01-01T13:00:00Z", 300, 201);

        // 失败分支均未落库：共 4 条读数
        mockMvc.perform(get("/api/equipment/eq-mono/readings"))
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void addReading_sameTimestampAndDuplicateId_rejected() throws Exception {
        register("eq-same", 1000);
        addReading("eq-same", "s1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        // 同一设备同一时刻仅一条
        addReading("eq-same", "s2", 2, "r2", "2026-01-01T10:00:00Z", 150, 422);
        // readingId 设备内唯一
        addReading("eq-same", "s3", 2, "r1", "2026-01-01T11:00:00Z", 150, 409);
    }

    // ---------- 修订 ----------

    @Test
    void revise_keepsHistoryAndValidatesNeighbors() throws Exception {
        register("eq-rev", 1000);
        addReading("eq-rev", "rv1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-rev", "rv2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 修订只改累计分钟，不改采样时刻；修订号 +1
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv3","expectedVersion":3,"cumulativeMinutes":150}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampledAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.cumulativeMinutes").value(150))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        // 历史保留：修订 1（初始值 100）与修订 2（150）
        mockMvc.perform(get("/api/equipment/eq-rev/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(100))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(150));

        // 修订须同时符合前后相邻值
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv4","expectedVersion":4,"cumulativeMinutes":250}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv5","expectedVersion":4,"cumulativeMinutes":50}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 保养锚点与证据门禁规则 ----------

    @Test
    void maintenance_anchorAndEvidenceGateRules() throws Exception {
        register("eq-mnt", 1000);
        addReading("eq-mnt", "a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-mnt", "a2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        registerMaterial("m-a", "润滑脂", "kg");
        registerMaterial("m-b", "密封圈", "个");

        // 无模板时完工：422 证据未齐
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-x0","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1,
                                 "expectedPackageVersion":1,"confirmedBy":"alice"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"));

        // 模板含必检项 c1、c2 与非必检项 c3
        mockMvc.perform(post("/api/equipment/eq-mnt/template/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tpl-mnt","expectedVersion":3,"items":[
                                  {"itemCode":"c1","itemName":"检查1","required":true},
                                  {"itemCode":"c2","itemName":"检查2","required":true},
                                  {"itemCode":"c3","itemName":"检查3","required":false}]}
                                """))
                .andExpect(status().isCreated());

        // 证据包版本 1：c2 FAIL（提交允许 FAIL，固化为不可变包），含材料 m-a
        mockMvc.perform(post("/api/equipment/eq-mnt/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-bad","expectedVersion":4,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null},
                                           {"itemCode":"c2","result":"FAIL","note":"漏油"}],
                                 "materials":[{"materialId":"m-a","quantity":1}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageVersion").value(1));

        // 完工门禁：存在 FAIL（c2）→ 422 列明失败项；不推进周期、不生成保养
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-fail","expectedVersion":5,"readingId":"r1","anchorRevisionNo":1,
                                 "expectedPackageVersion":1,"confirmedBy":"alice"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("c2"))));
        mockMvc.perform(get("/api/equipment/eq-mnt/maintenances"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 提交不合规结果：引用不存在的检查项 cX → EVIDENCE_INVALID，整次回滚不留半成品
        mockMvc.perform(post("/api/equipment/eq-mnt/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-invalid","expectedVersion":5,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null},
                                           {"itemCode":"c2","result":"PASS","note":null},
                                           {"itemCode":"cX","result":"PASS","note":null}],
                                 "materials":[{"materialId":"m-b","quantity":1}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INVALID"))
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("cX"))));
        // 回滚后最新版本仍为 1，周期内材料 m-b 未被占用
        mockMvc.perform(get("/api/equipment/eq-mnt/cycles/current"))
                .andExpect(jsonPath("$.currentEvidenceVersion").value(1));
        mockMvc.perform(get("/api/equipment/eq-mnt/status"))
                .andExpect(jsonPath("$.version").value(5));

        // 提交合规证据包版本 2：必检项全 PASS + 新材料 m-b（m-a 在周期 1 已引用不可重复）
        mockMvc.perform(post("/api/equipment/eq-mnt/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-1","expectedVersion":5,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null},
                                           {"itemCode":"c2","result":"PASS","note":null}],
                                 "materials":[{"materialId":"m-b","quantity":2}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageVersion").value(2))
                .andExpect(jsonPath("$.checks[0].position").value(1))
                .andExpect(jsonPath("$.checks[1].itemCode").value("c2"))
                .andExpect(jsonPath("$.materials[0].materialId").value("m-b"))
                .andExpect(jsonPath("$.materials[0].unit").value("个"));
        // 历史证据包版本 1 原样保留（FAIL 与材料 m-a 不被覆盖）
        mockMvc.perform(get("/api/equipment/eq-mnt/cycles/1/evidence/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[1].result").value("FAIL"))
                .andExpect(jsonPath("$.materials[0].materialId").value("m-a"));

        // 锚点读数不存在 → 404
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-x","expectedVersion":6,"readingId":"nope","anchorRevisionNo":1,
                                 "expectedPackageVersion":2,"confirmedBy":"alice"}
                                """))
                .andExpect(status().isNotFound());

        // 锚点修订号与当前修订号不一致 → 409
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-x2","expectedVersion":6,"readingId":"r1","anchorRevisionNo":7,
                                 "expectedPackageVersion":2,"confirmedBy":"alice"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANCHOR_REVISION_CONFLICT"));

        // 提交人本人不能确认完工 → 422
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-self","expectedVersion":6,"readingId":"r1","anchorRevisionNo":1,
                                 "expectedPackageVersion":2,"confirmedBy":"bob"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("bob"))));

        // 证据包版本漂移：确认不存在的版本 3 → 409
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-drift","expectedVersion":6,"readingId":"r1","anchorRevisionNo":1,
                                 "expectedPackageVersion":3,"confirmedBy":"alice"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACKAGE_VERSION_CONFLICT"));

        // 另一维护角色确认版本 2，第一次保养完工
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-1","expectedVersion":6,"readingId":"r1","anchorRevisionNo":1,
                                 "expectedPackageVersion":2,"confirmedBy":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cycleNo").value(1));

        // 已完成周期不能重开：下一周期证据包版本为 0，确认旧版本 → 422 证据未齐，不产生第二条保养
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-2","expectedVersion":7,"readingId":"r2","anchorRevisionNo":1,
                                 "expectedPackageVersion":2,"confirmedBy":"alice"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"));
        mockMvc.perform(get("/api/equipment/eq-mnt/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 作为历史保养锚点的读数不可修订 → 409
        mockMvc.perform(post("/api/equipment/eq-mnt/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-3","expectedVersion":7,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));

        // 下一周期提交含 m-b 的证据包版本 1（材料批次按周期隔离，周期 2 可再次引用）
        mockMvc.perform(post("/api/equipment/eq-mnt/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev2-1","expectedVersion":7,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null},
                                           {"itemCode":"c2","result":"PASS","note":null}],
                                 "materials":[{"materialId":"m-b","quantity":1}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cycleNo").value(2))
                .andExpect(jsonPath("$.packageVersion").value(1));
        // 同设备同周期重复引用 m-b → 422
        mockMvc.perform(post("/api/equipment/eq-mnt/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev2-dup","expectedVersion":8,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null},
                                           {"itemCode":"c2","result":"PASS","note":null}],
                                 "materials":[{"materialId":"m-b","quantity":3}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.containsString("m-b"))));

        // 非锚点读数可正常修订，随后以其新修订号完成第二次保养
        mockMvc.perform(post("/api/equipment/eq-mnt/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-4","expectedVersion":8,"cumulativeMinutes":210}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2));
        // 读数修订改变设备版本，证据包仍是周期 2 最新版本（证据不要求随读数重新提交）
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-5","expectedVersion":9,"readingId":"r2","anchorRevisionNo":2,
                                 "expectedPackageVersion":1,"confirmedBy":"carol"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(210))
                .andExpect(jsonPath("$.cycleNo").value(2))
                .andExpect(jsonPath("$.evidencePackageVersion").value(1));

        // 保养记录保存锚点快照且不允许删除（无删除接口），历史完整
        mockMvc.perform(get("/api/equipment/eq-mnt/maintenances"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(100))
                .andExpect(jsonPath("$[0].cycleNo").value(1))
                .andExpect(jsonPath("$[1].anchorCumulativeMinutes").value(210))
                .andExpect(jsonPath("$[1].cycleNo").value(2));
    }

    // ---------- 幂等 ----------

    @Test
    void idempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-idem", 1000);

        MvcResult first = postJson("/api/equipment/eq-idem/readings", """
                {"requestId":"idem-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);
        assert first.getResponse().getStatus() == 201;

        // 同键同参：重放原成功结果，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-idem/readings", """
                {"requestId":"idem-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                """);
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-idem/readings"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-idem/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-idem/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":101}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 后同 requestId 修正参数可成功
        addReading("eq-idem", "idem-2", 2, "r2", "2026-01-01T11:00:00Z", 50, 422);
        addReading("eq-idem", "idem-2", 2, "r2", "2026-01-01T11:00:00Z", 150, 201);

        // 版本冲突失败同样不占键
        addReading("eq-idem", "idem-3", 99, "r3", "2026-01-01T12:00:00Z", 200, 409);
        addReading("eq-idem", "idem-3", 3, "r3", "2026-01-01T12:00:00Z", 200, 201);

        mockMvc.perform(get("/api/equipment/eq-idem/readings"))
                .andExpect(jsonPath("$", hasSize(3)));
    }

    // ---------- 资源不存在 ----------

    @Test
    void notFound_branches() throws Exception {
        mockMvc.perform(get("/api/equipment/nope/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope/readings"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/nope/maintenances"))
                .andExpect(status().isNotFound());
        addReading("nope", "nf-1", 1, "r1", "2026-01-01T10:00:00Z", 1, 404);

        register("eq-nf", 100);
        mockMvc.perform(post("/api/equipment/eq-nf/readings/nope/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nf-2","expectedVersion":1,"cumulativeMinutes":10}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/eq-nf/readings/nope/revisions"))
                .andExpect(status().isNotFound());
    }
}
