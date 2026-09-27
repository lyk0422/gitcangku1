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
 * 证据包、模板版本、材料台账与双人完工门禁的真实 H2 数据库测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM evidence_appendix");
        jdbc.update("DELETE FROM maintenance_confirmation");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM evidence_material");
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

    /** 登记设备（版本 1）并创建模板版本 1（必检项 I1/I2，阈值 200），结束时设备版本为 2。 */
    private void setupEquipmentWithTemplate(String equipmentId, long period, long threshold) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, period)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/template-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tpl-%s","expectedVersion":1,
                                 "overdueThresholdMinutes":%d,
                                 "items":[{"itemCode":"I1","itemTitle":"润滑"},
                                          {"itemCode":"I2","itemTitle":"紧固"}]}
                                """.formatted(equipmentId, threshold)))
                .andExpect(status().isCreated());
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long minutes) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt, minutes)))
                .andExpect(status().isCreated());
    }

    private void registerMaterial(String requestId, String batchCode) throws Exception {
        mockMvc.perform(post("/api/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","batchCode":"%s","description":"合成材料"}
                                """.formatted(requestId, batchCode)))
                .andExpect(status().isCreated());
    }

    private String maintenanceBody(String requestId, long expectedVersion, String readingId,
                                   int packageVersion, int templateVersion, String confirmer) {
        return """
                {"requestId":"%s","expectedVersion":%d,"readingId":"%s","anchorRevisionNo":1,
                 "expectedPackageVersion":%d,"expectedTemplateVersion":%d,"confirmerId":"%s"}
                """.formatted(requestId, expectedVersion, readingId,
                packageVersion, templateVersion, confirmer);
    }

    // ---------- 证据包主流程：多版本递增、排序、固化与双人完工 ----------

    @Test
    void evidence_multiVersionCompletionAndImmutableHistory() throws Exception {
        setupEquipmentWithTemplate("eq-ev", 100, 200);
        addReading("eq-ev", "ev-r1", 2, "r1", "2026-02-01T10:00:00Z", 100);
        addReading("eq-ev", "ev-r2", 3, "r2", "2026-02-01T11:00:00Z", 130);
        registerMaterial("ev-mat", "MAT-EV0001");

        // 版本 1：仅 I1 PASS + 材料，最终包不齐，缺 I2
        mockMvc.perform(post("/api/equipment/eq-ev/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-1","expectedVersion":4,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS","remark":"正常"}],
                                 "materials":[{"batchCode":"MAT-EV0001","quantity":2,"unit":"PCS"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.packageVersion").value(1))
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.submittedBy").value("alice"))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.missingItems", hasSize(1)))
                .andExpect(jsonPath("$.missingItems[0]").value("I2"))
                .andExpect(jsonPath("$.checkResults", hasSize(1)))
                .andExpect(jsonPath("$.materials", hasSize(1)))
                .andExpect(jsonPath("$.materials[0].addedVersion").value(1))
                .andExpect(jsonPath("$.materials[0].quantity").value(2));

        // 读取不改变状态：设备版本仍为 5
        mockMvc.perform(get("/api/equipment/eq-ev/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageVersion").value(1));
        mockMvc.perform(get("/api/equipment/eq-ev/status"))
                .andExpect(jsonPath("$.version").value(5));

        // 版本 2：补齐 I2 PASS；最终结果按模板 ordinal 排序，材料摘要保留
        mockMvc.perform(post("/api/equipment/eq-ev/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-2","expectedVersion":5,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I2","result":"PASS"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageVersion").value(2))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.missingItems", hasSize(0)))
                .andExpect(jsonPath("$.checkResults", hasSize(2)))
                .andExpect(jsonPath("$.checkResults[0].itemCode").value("I1"))
                .andExpect(jsonPath("$.checkResults[0].result").value("PASS"))
                .andExpect(jsonPath("$.checkResults[0].packageVersion").value(1))
                .andExpect(jsonPath("$.checkResults[1].itemCode").value("I2"))
                .andExpect(jsonPath("$.checkResults[1].packageVersion").value(2))
                .andExpect(jsonPath("$.materials", hasSize(1)));

        // 历史版本 1 不可变：仍缺 I2
        mockMvc.perform(get("/api/equipment/eq-ev/cycles/1/evidence/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageVersion").value(1))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.missingItems[0]").value("I2"))
                .andExpect(jsonPath("$.checkResults", hasSize(1)));

        // 确认旧版本 → 409；确认固化模板版本错误 → 409
        mockMvc.perform(post("/api/equipment/eq-ev/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("mnt-stale", 6, "r2", 1, 1, "bob")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVIDENCE_VERSION_CONFLICT"));
        mockMvc.perform(post("/api/equipment/eq-ev/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("mnt-tpl", 6, "r2", 2, 2, "bob")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_VERSION_CONFLICT"));

        // 另一维护人员确认当前版本 → 原子完工
        mockMvc.perform(post("/api/equipment/eq-ev/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("mnt-ok", 6, "r2", 2, 1, "bob")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.packageVersion").value(2))
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.submitterId").value("alice"))
                .andExpect(jsonPath("$.confirmerId").value("bob"));

        // 周期推进，周期 1 证据仍可按版本查看
        mockMvc.perform(get("/api/equipment/eq-ev/status"))
                .andExpect(jsonPath("$.currentCycleNo").value(2));
        mockMvc.perform(get("/api/equipment/eq-ev/cycles/1/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.packageVersion").value(2));
    }

    // ---------- 模板修订不改写旧周期，新周期使用新版本 ----------

    @Test
    void templateRevision_isolatesCycles() throws Exception {
        setupEquipmentWithTemplate("eq-tpl", 100, 200);
        addReading("eq-tpl", "tr-r1", 2, "r1", "2026-02-01T10:00:00Z", 130);
        registerMaterial("tr-m1", "MAT-TPL001");
        mockMvc.perform(post("/api/equipment/eq-tpl/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tr-ev1","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"},
                                                 {"itemCode":"I2","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-TPL001","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-tpl/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("tr-m1c", 4, "r1", 1, 1, "bob")))
                .andExpect(status().isCreated());

        // 模板版本 2：更换必检项、提高阈值（设备版本 5 → 6）
        mockMvc.perform(post("/api/equipment/eq-tpl/template-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tr-tpl2","expectedVersion":5,
                                 "overdueThresholdMinutes":300,
                                 "items":[{"itemCode":"I3","itemTitle":"绝缘"},
                                          {"itemCode":"I4","itemTitle":"校验"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateVersion").value(2));

        // 历史模板版本与最新版本各自独立
        mockMvc.perform(get("/api/equipment/eq-tpl/template-versions/1"))
                .andExpect(jsonPath("$.items[0].itemCode").value("I1"))
                .andExpect(jsonPath("$.items[1].itemCode").value("I2"));
        mockMvc.perform(get("/api/equipment/eq-tpl/template-versions/latest"))
                .andExpect(jsonPath("$.templateVersion").value(2))
                .andExpect(jsonPath("$.items[0].itemCode").value("I3"));

        // 周期 2 固化模板版本 2：提交旧项 I1 被拒绝
        mockMvc.perform(post("/api/equipment/eq-tpl/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tr-ev2bad","expectedVersion":6,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-TPL001","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_ITEM_NOT_IN_TEMPLATE"));

        // 失败回滚：周期 2 仍无证据包
        mockMvc.perform(get("/api/equipment/eq-tpl/evidence"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_FOUND"));
    }

    // ---------- 一次更新多检查项：先校验完整最终包，任一失败整次回滚 ----------

    @Test
    void evidence_invalidBatchUpdate_rollsBackNoHalfPackage() throws Exception {
        setupEquipmentWithTemplate("eq-roll", 100, 200);
        addReading("eq-roll", "rl-r1", 2, "r1", "2026-02-01T10:00:00Z", 130);
        registerMaterial("rl-m", "MAT-ROL001");

        // 同次更新重复检查项 → 422
        mockMvc.perform(post("/api/equipment/eq-roll/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-bad1","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"},
                                                 {"itemCode":"I1","result":"FAIL"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_DUPLICATE_ITEM"));

        // 结果取值非法 → 422
        mockMvc.perform(post("/api/equipment/eq-roll/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-bad2","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"YES"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INVALID_RESULT"));

        // 检查项不属于模板 → 422
        mockMvc.perform(post("/api/equipment/eq-roll/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-bad3","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"},
                                                 {"itemCode":"I9","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-ROL001","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_ITEM_NOT_IN_TEMPLATE"));

        // 材料编码格式非法 → 422
        mockMvc.perform(post("/api/equipment/eq-roll/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-bad4","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"},
                                                 {"itemCode":"I2","result":"PASS"}],
                                 "materials":[{"batchCode":"bad-0001","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_FORMAT_INVALID"));

        // 材料台账不存在 → 422
        mockMvc.perform(post("/api/equipment/eq-roll/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-bad5","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"},
                                                 {"itemCode":"I2","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-ZZZZZZZZ","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_REFERENCE_INVALID"));

        // 引用已撤销材料 → 422
        registerMaterial("rl-m2", "MAT-ROL002");
        mockMvc.perform(post("/api/materials/MAT-ROL002/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-rev","reason":"批次召回"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-roll/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-bad6","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"},
                                                 {"itemCode":"I2","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-ROL002","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_REVOKED"));

        // 空更新 → 422
        mockMvc.perform(post("/api/equipment/eq-roll/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-bad7","expectedVersion":3,"submittedBy":"alice"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_EMPTY_UPDATE"));

        // 所有失败整次回滚：无任何证据包版本、设备版本停留在 3、无半成品可查
        mockMvc.perform(get("/api/equipment/eq-roll/evidence"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/eq-roll/status"))
                .andExpect(jsonPath("$.version").value(3));
        Integer packageRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_package WHERE equipment_id = 'eq-roll'", Integer.class);
        Integer resultRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_check_result WHERE equipment_id = 'eq-roll'", Integer.class);
        Integer materialRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_material WHERE equipment_id = 'eq-roll'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, packageRows);
        org.junit.jupiter.api.Assertions.assertEquals(0, resultRows);
        org.junit.jupiter.api.Assertions.assertEquals(0, materialRows);
    }

    // ---------- 材料摘要周期内不可重复；跨周期可复用 ----------

    @Test
    void materialSummary_uniquePerCycle_reusableNextCycle() throws Exception {
        setupEquipmentWithTemplate("eq-mat", 100, 200);
        addReading("eq-mat", "mm-r1", 2, "r1", "2026-02-01T10:00:00Z", 100);
        addReading("eq-mat", "mm-r2", 3, "r2", "2026-02-01T11:00:00Z", 130);
        registerMaterial("mm-m", "MAT-MAT0001");

        // 同一次更新内批次重复 → 422 回滚
        mockMvc.perform(post("/api/equipment/eq-mat/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mm-bad","expectedVersion":4,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-MAT0001","quantity":1,"unit":"PCS"},
                                              {"batchCode":"MAT-MAT0001","quantity":2,"unit":"PCS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_DUPLICATE_IN_CYCLE"));
        mockMvc.perform(get("/api/equipment/eq-mat/status"))
                .andExpect(jsonPath("$.version").value(4));

        // 版本 1：I1 PASS + 材料
        mockMvc.perform(post("/api/equipment/eq-mat/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mm-v1","expectedVersion":4,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-MAT0001","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isCreated());

        // 版本 2 再引用同批次 → 422，证据包版本不增加、设备版本不变
        mockMvc.perform(post("/api/equipment/eq-mat/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mm-v2bad","expectedVersion":5,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I2","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-MAT0001","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_DUPLICATE_IN_CYCLE"));
        mockMvc.perform(get("/api/equipment/eq-mat/evidence"))
                .andExpect(jsonPath("$.packageVersion").value(1));
        mockMvc.perform(get("/api/equipment/eq-mat/status"))
                .andExpect(jsonPath("$.version").value(5));

        // 不带材料补 I2 → 齐备并完工
        mockMvc.perform(post("/api/equipment/eq-mat/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mm-v2","expectedVersion":5,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I2","result":"PASS"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.complete").value(true));
        mockMvc.perform(post("/api/equipment/eq-mat/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("mm-done", 6, "r2", 2, 1, "bob")))
                .andExpect(status().isCreated());

        // 周期 2 可复用同一批次（唯一约束范围为设备+周期）
        addReading("eq-mat", "mm-r3", 7, "r3", "2026-02-01T12:00:00Z", 230);
        mockMvc.perform(post("/api/equipment/eq-mat/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mm-c2","expectedVersion":8,"submittedBy":"carol",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"},
                                                 {"itemCode":"I2","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-MAT0001","quantity":3,"unit":"KG"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cycleNo").value(2))
                .andExpect(jsonPath("$.materials[0].quantity").value(3))
                .andExpect(jsonPath("$.materials[0].unit").value("KG"));
    }

    // ---------- 完工门禁：证据不齐、FAIL、提交人自确认、OVERDUE ----------

    @Test
    void completion_gates_422WithReasons() throws Exception {
        setupEquipmentWithTemplate("eq-gate", 100, 200);
        addReading("eq-gate", "g-r1", 2, "r1", "2026-02-01T10:00:00Z", 130);
        registerMaterial("g-m", "MAT-GATE01");

        // 无证据直接完工 → 422
        mockMvc.perform(post("/api/equipment/eq-gate/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("g-noev", 3, "r1", 1, 1, "bob")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_INCOMPLETE"));

        // 仅 I1 PASS + 材料：缺 I2，消息须列出缺失项
        mockMvc.perform(post("/api/equipment/eq-gate/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g-v1","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-GATE01","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-gate/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("g-miss", 4, "r1", 1, 1, "bob")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("I2")));

        // I2 FAIL：消息列出失败项
        mockMvc.perform(post("/api/equipment/eq-gate/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g-v2","expectedVersion":4,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I2","result":"FAIL","remark":"力矩不足"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.failedItems[0]").value("I2"));
        mockMvc.perform(post("/api/equipment/eq-gate/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("g-fail", 5, "r1", 2, 1, "bob")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("FAIL")));

        // 改回 PASS 后提交人自确认 → 422
        mockMvc.perform(post("/api/equipment/eq-gate/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"g-v3","expectedVersion":5,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I2","result":"PASS"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.complete").value(true));
        mockMvc.perform(post("/api/equipment/eq-gate/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("g-self", 6, "r1", 3, 1, "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIRMER_IS_SUBMITTER"));
    }

    @Test
    void completion_overdue_422AndStatusBoundaries() throws Exception {
        setupEquipmentWithTemplate("eq-over", 100, 200);
        registerMaterial("o-m", "MAT-OVER01");

        // 区间边界：199 DUE，200 OVERDUE（左闭右开）
        addReading("eq-over", "o-r199", 2, "r1", "2026-02-01T10:00:00Z", 199);
        mockMvc.perform(get("/api/equipment/eq-over/status"))
                .andExpect(jsonPath("$.runMinutes").value(199))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.overdueByMinutes").value(0));

        mockMvc.perform(post("/api/equipment/eq-over/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"o-r200","expectedVersion":3,"cumulativeMinutes":200}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-over/status"))
                .andExpect(jsonPath("$.runMinutes").value(200))
                .andExpect(jsonPath("$.status").value("OVERDUE"))
                .andExpect(jsonPath("$.overdueByMinutes").value(0));

        mockMvc.perform(post("/api/equipment/eq-over/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"o-r250","expectedVersion":4,"cumulativeMinutes":250}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-over/status"))
                .andExpect(jsonPath("$.runMinutes").value(250))
                .andExpect(jsonPath("$.overdueByMinutes").value(50))
                .andExpect(jsonPath("$.status").value("OVERDUE"));

        // 证据齐备但设备 OVERDUE：完工 422，消息给出实际值/要求值/差额（锚点已修订两次，当前修订号 3）
        mockMvc.perform(post("/api/equipment/eq-over/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"o-ev","expectedVersion":5,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"},
                                                 {"itemCode":"I2","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-OVER01","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-over/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"o-mnt","expectedVersion":6,"readingId":"r1","anchorRevisionNo":3,
                                 "expectedPackageVersion":1,"expectedTemplateVersion":1,"confirmerId":"bob"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_OVERDUE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("250")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("200")));

        // 失败回滚：无保养、无确认
        Integer maintenances = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = 'eq-over'", Integer.class);
        Integer confirmations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_confirmation WHERE equipment_id = 'eq-over'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, maintenances);
        org.junit.jupiter.api.Assertions.assertEquals(0, confirmations);
    }

    // ---------- 材料台账 ----------

    @Test
    void material_registerRevokeAndQuery() throws Exception {
        mockMvc.perform(post("/api/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-1","batchCode":"MAT-LIFE01","description":"密封件"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.revokedAt").doesNotExist());

        // 格式非法 → 422
        mockMvc.perform(post("/api/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-2","batchCode":"mat-life01"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_FORMAT_INVALID"));

        // 批次重复 → 409
        mockMvc.perform(post("/api/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-3","batchCode":"MAT-LIFE01"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATERIAL_EXISTS"));

        mockMvc.perform(get("/api/materials/MAT-LIFE01"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 撤销
        mockMvc.perform(post("/api/materials/MAT-LIFE01/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-4","reason":"供应商召回"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeReason").value("供应商召回"))
                .andExpect(jsonPath("$.revokedAt").exists());

        // 重复撤销 → 409
        mockMvc.perform(post("/api/materials/MAT-LIFE01/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-5","reason":"再次撤销"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATERIAL_ALREADY_REVOKED"));

        // 撤销不存在 → 404
        mockMvc.perform(post("/api/materials/MAT-MISSING/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-6"}
                                """))
                .andExpect(status().isNotFound());
    }

    // ---------- 模板校验 ----------

    @Test
    void template_thresholdAndDuplicateItemRejected() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tt-reg","equipmentId":"eq-tt","maintenancePeriodMinutes":100}
                                """))
                .andExpect(status().isCreated());

        // 阈值等于周期 → 422
        mockMvc.perform(post("/api/equipment/eq-tt/template-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tt-1","expectedVersion":1,"overdueThresholdMinutes":100,
                                 "items":[{"itemCode":"I1","itemTitle":"x"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("THRESHOLD_INVALID"));

        // 同模板检查项重复 → 422
        mockMvc.perform(post("/api/equipment/eq-tt/template-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tt-2","expectedVersion":1,"overdueThresholdMinutes":200,
                                 "items":[{"itemCode":"I1","itemTitle":"x"},
                                          {"itemCode":"I1","itemTitle":"y"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEMPLATE_DUPLICATE_ITEM"));

        // 失败不推进版本，正常模板随后可建
        mockMvc.perform(get("/api/equipment/eq-tt/status"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(post("/api/equipment/eq-tt/template-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tt-3","expectedVersion":1,"overdueThresholdMinutes":200,
                                 "items":[{"itemCode":"I1","itemTitle":"x"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateVersion").value(1));

        // 无模板设备不能提交证据
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tt-reg2","equipmentId":"eq-tt2","maintenancePeriodMinutes":100}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-tt2/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tt-ev","expectedVersion":1,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
    }

    // ---------- 更正附录 ----------

    @Test
    void appendix_onlyForCompletedCycle_andAppends() throws Exception {
        setupEquipmentWithTemplate("eq-ap", 100, 200);
        addReading("eq-ap", "ap-r1", 2, "r1", "2026-02-01T10:00:00Z", 130);
        registerMaterial("ap-m", "MAT-APP001");
        mockMvc.perform(post("/api/equipment/eq-ap/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ap-ev","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"},
                                                 {"itemCode":"I2","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-APP001","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isCreated());

        // 周期未完成 → 不能提前附录
        mockMvc.perform(post("/api/equipment/eq-ap/cycles/1/appendices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ap-x1","operatorId":"dave","note":"发现扭矩记录有误"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CYCLE_NOT_COMPLETED"));

        mockMvc.perform(post("/api/equipment/eq-ap/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceBody("ap-done", 4, "r1", 1, 1, "bob")))
                .andExpect(status().isCreated());

        // 完工后只可追加附录；工时历史与证据不变
        mockMvc.perform(post("/api/equipment/eq-ap/cycles/1/appendices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ap-x2","operatorId":"dave","note":"发现扭矩记录有误，待下周期复测"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.operatorId").value("dave"));
        mockMvc.perform(post("/api/equipment/eq-ap/cycles/1/appendices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ap-x3","operatorId":"dave","note":"补充现场照片编号 P-42"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-ap/cycles/1/appendices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].note").value("发现扭矩记录有误，待下周期复测"))
                .andExpect(jsonPath("$[1].note").value("补充现场照片编号 P-42"));

        // 周期 2 未完成：查询附录 404
        mockMvc.perform(get("/api/equipment/eq-ap/cycles/2/appendices"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CYCLE_NOT_FOUND"));

        // 原周期证据与保养快照未被附录改动
        mockMvc.perform(get("/api/equipment/eq-ap/cycles/1/evidence"))
                .andExpect(jsonPath("$.packageVersion").value(1))
                .andExpect(jsonPath("$.complete").value(true));
        mockMvc.perform(get("/api/equipment/eq-ap/maintenances"))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(130));
    }

    // ---------- 证据包幂等 ----------

    @Test
    void evidence_idempotency_replayMismatchFailureNotOccupying() throws Exception {
        setupEquipmentWithTemplate("eq-ie", 100, 200);
        addReading("eq-ie", "ie-r1", 2, "r1", "2026-02-01T10:00:00Z", 130);
        registerMaterial("ie-m", "MAT-IDEM01");

        MvcResult first = postJson("/api/equipment/eq-ie/evidence", """
                {"requestId":"ie-1","expectedVersion":3,"submittedBy":"alice",
                 "checkResults":[{"itemCode":"I1","result":"PASS"}],
                 "materials":[{"batchCode":"MAT-IDEM01","quantity":1,"unit":"PCS"}]}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        // 同键同参重放，响应体一致且不产生第二版本
        MvcResult replay = postJson("/api/equipment/eq-ie/evidence", """
                {"requestId":"ie-1","expectedVersion":3,"submittedBy":"alice",
                 "checkResults":[{"itemCode":"I1","result":"PASS"}],
                 "materials":[{"batchCode":"MAT-IDEM01","quantity":1,"unit":"PCS"}]}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-ie/evidence"))
                .andExpect(jsonPath("$.packageVersion").value(1));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-ie/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ie-1","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I2","result":"PASS"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：同 requestId 先因不属于模板失败，修正后成功
        mockMvc.perform(post("/api/equipment/eq-ie/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ie-2","expectedVersion":4,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I9","result":"PASS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/equipment/eq-ie/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ie-2","expectedVersion":4,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I2","result":"PASS"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageVersion").value(2))
                .andExpect(jsonPath("$.complete").value(true));
    }
}
