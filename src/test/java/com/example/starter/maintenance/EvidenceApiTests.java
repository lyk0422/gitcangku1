package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * 保养证据包与双人完工确认 API 测试（真实 H2 内存库，MySQL 兼容模式）。
 * 覆盖主流程、失败分支、整包回滚、版本冲突、不可变性与幂等边界。
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
        jdbc.update("DELETE FROM maintenance_appendix");
        jdbc.update("DELETE FROM completion_confirmation");
        jdbc.update("DELETE FROM evidence_material");
        jdbc.update("DELETE FROM evidence_item_result");
        jdbc.update("DELETE FROM evidence_package");
        jdbc.update("DELETE FROM material_batch");
        jdbc.update("DELETE FROM maintenance_template_item");
        jdbc.update("DELETE FROM maintenance_template_head");
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    // ---------- 测试辅助 ----------

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void createTemplate(String templateId, String requestId, String itemsJson)
            throws Exception {
        mockMvc.perform(post("/api/maintenance-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","templateId":"%s","items":%s}
                                """.formatted(requestId, templateId, itemsJson)))
                .andExpect(status().isCreated());
    }

    private void createStandardTemplate(String templateId) throws Exception {
        createTemplate(templateId, "ct-" + templateId, """
                [{"itemCode":"oil","itemName":"机油更换","required":true,"sortOrder":1},
                 {"itemCode":"filter","itemName":"滤芯检查","required":true,"sortOrder":2},
                 {"itemCode":"belt","itemName":"皮带检查","required":false,"sortOrder":3}]
                """);
    }

    private void registerMaterial(String batchCode) throws Exception {
        mockMvc.perform(post("/api/material-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rm-%s","batchCode":"%s","materialName":"材料-%s"}
                                """.formatted(batchCode, batchCode, batchCode)))
                .andExpect(status().isCreated());
    }

    private void registerEquipment(String equipmentId, long period, String templateId)
            throws Exception {
        String templateField = templateId == null ? "" : ",\"templateId\":\"" + templateId + "\"";
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d%s}
                                """.formatted(equipmentId, equipmentId, period, templateField)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.currentCycleNo").value(1));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes)
            throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    private MvcResult submitEvidence(String equipmentId, String requestId, long expectedVersion,
                                     String submittedBy, String itemResultsJson, String materialsJson)
            throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/evidence-packages", """
                {"requestId":"%s","expectedVersion":%d,"submittedBy":"%s",
                 "itemResults":%s,"materials":%s}
                """.formatted(requestId, expectedVersion, submittedBy, itemResultsJson,
                materialsJson));
    }

    private MvcResult confirm(String equipmentId, String requestId, long expectedVersion,
                              String confirmedBy, int packageVersion, int templateVersion,
                              String readingId, int anchorRevisionNo) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/completion-confirmations", """
                {"requestId":"%s","expectedVersion":%d,"confirmedBy":"%s",
                 "packageVersion":%d,"templateVersion":%d,"readingId":"%s","anchorRevisionNo":%d}
                """.formatted(requestId, expectedVersion, confirmedBy, packageVersion,
                templateVersion, readingId, anchorRevisionNo));
    }

    // ---------- 主流程：证据提交 → 双人确认 → 周期重置 → 历史与附录 ----------

    @Test
    void mainFlow_evidenceDualConfirmationAndCycleReset() throws Exception {
        createStandardTemplate("tpl-main");
        registerMaterial("BATCH-001");
        registerMaterial("BATCH-002");
        registerEquipment("eq-main", 100, "tpl-main");
        addReading("eq-main", "ar-1", 1, "r1", "2026-01-01T10:00:00Z", 50);
        addReading("eq-main", "ar-2", 2, "r2", "2026-01-01T11:00:00Z", 130);

        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(130))
                .andExpect(jsonPath("$.minutesUntilDue").value(-30))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 诊断：未提交证据包，必检项全部缺失
        mockMvc.perform(get("/api/equipment/eq-main/evidence-diagnostics"))
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.currentPackageVersion").value(nullValue()))
                .andExpect(jsonPath("$.requiredItemCount").value(2))
                .andExpect(jsonPath("$.passedRequiredItemCount").value(0))
                .andExpect(jsonPath("$.missingRequiredItems", hasSize(2)))
                .andExpect(jsonPath("$.missingRequiredItems[0]").value("oil"))
                .andExpect(jsonPath("$.missingRequiredItems[1]").value("filter"))
                .andExpect(jsonPath("$.materialCount").value(0))
                .andExpect(jsonPath("$.readyForCompletion").value(false));

        // 提交 v1：仅一个必检项 PASS
        MvcResult v1 = submitEvidence("eq-main", "se-1", 3, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\",\"note\":\"已更换\"}]",
                "[{\"batchCode\":\"BATCH-001\",\"quantity\":2,\"unit\":\"pcs\",\"summary\":\"机油滤芯\"}]");
        assertEquals(201, v1.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-main/evidence-diagnostics"))
                .andExpect(jsonPath("$.currentPackageVersion").value(1))
                .andExpect(jsonPath("$.passedRequiredItemCount").value(1))
                .andExpect(jsonPath("$.missingRequiredItems", hasSize(1)))
                .andExpect(jsonPath("$.missingRequiredItems[0]").value("filter"))
                .andExpect(jsonPath("$.materialCount").value(1))
                .andExpect(jsonPath("$.readyForCompletion").value(false));

        // 证据未齐：既有完成保养 422 并列出缺失项
        mockMvc.perform(post("/api/equipment/eq-main/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-x2","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath("$.details[0].type").value("MISSING_REQUIRED_ITEM"))
                .andExpect(jsonPath("$.details[0].target").value("filter"));

        // 提交 v2：全部必检项 PASS（材料批次不可与 v1 重复，使用 BATCH-002）
        mockMvc.perform(post("/api/equipment/eq-main/evidence-packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"se-2","expectedVersion":4,"submittedBy":"tech-a",
                                 "itemResults":[{"itemCode":"filter","result":"PASS"},
                                                {"itemCode":"oil","result":"PASS"},
                                                {"itemCode":"belt","result":"PASS"}],
                                 "materials":[{"batchCode":"BATCH-002","summary":"皮带组件"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageVersion").value(2))
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.equipmentVersion").value(5))
                // 规范排序：按模板 sortOrder 排序，而非提交顺序
                .andExpect(jsonPath("$.items[0].itemCode").value("oil"))
                .andExpect(jsonPath("$.items[1].itemCode").value("filter"))
                .andExpect(jsonPath("$.items[2].itemCode").value("belt"))
                .andExpect(jsonPath("$.materials[0].batchCode").value("BATCH-002"))
                .andExpect(jsonPath("$.materials[0].quantity").value(nullValue()));

        mockMvc.perform(get("/api/equipment/eq-main/evidence-diagnostics"))
                .andExpect(jsonPath("$.readyForCompletion").value(true));

        // 证据齐备：既有完成保养仍须双人确认
        mockMvc.perform(post("/api/equipment/eq-main/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-y","expectedVersion":5,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUAL_CONFIRMATION_REQUIRED"));

        // 提交人不能确认完工
        mockMvc.perform(post("/api/equipment/eq-main/completion-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-1","expectedVersion":5,"confirmedBy":"tech-a",
                                 "packageVersion":2,"templateVersion":1,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIRMER_IS_SUBMITTER"));

        // 证据包版本不一致 → 409
        mockMvc.perform(post("/api/equipment/eq-main/completion-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-2","expectedVersion":5,"confirmedBy":"tech-b",
                                 "packageVersion":1,"templateVersion":1,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACKAGE_VERSION_CONFLICT"));

        // 另一维护角色确认当前证据包版本 → 原子完成保养并重置下一周期
        MvcResult confirmed = mockMvc.perform(post("/api/equipment/eq-main/completion-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-3","expectedVersion":5,"confirmedBy":"tech-b",
                                 "packageVersion":2,"templateVersion":1,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.nextCycleNo").value(2))
                .andExpect(jsonPath("$.packageVersion").value(2))
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.confirmedBy").value("tech-b"))
                .andExpect(jsonPath("$.equipmentVersion").value(6))
                .andReturn();
        long maintenanceId = ((Number) JsonPath.read(confirmed.getResponse().getContentAsString(),
                "$.maintenanceId")).longValue();

        // 周期重置：状态从锚点重算，诊断进入第 2 周期
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
        mockMvc.perform(get("/api/equipment/eq-main/evidence-diagnostics"))
                .andExpect(jsonPath("$.cycleNo").value(2))
                .andExpect(jsonPath("$.currentPackageVersion").value(nullValue()))
                .andExpect(jsonPath("$.readyForCompletion").value(false));

        // 历史：两个证据包版本完整保留（不可变快照）
        mockMvc.perform(get("/api/equipment/eq-main/evidence-packages"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].cycleNo").value(1))
                .andExpect(jsonPath("$[0].packageVersion").value(1))
                .andExpect(jsonPath("$[0].itemCount").value(1))
                .andExpect(jsonPath("$[1].packageVersion").value(2))
                .andExpect(jsonPath("$[1].itemCount").value(3));
        mockMvc.perform(get("/api/equipment/eq-main/evidence-packages/1/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.submittedBy").value("tech-a"))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.materials", hasSize(1)));
        // 新周期尚无证据包
        mockMvc.perform(get("/api/equipment/eq-main/evidence-packages/current"))
                .andExpect(status().isNotFound());

        // 保养记录锚点快照
        mockMvc.perform(get("/api/equipment/eq-main/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(130));

        // 更正附录：只能追加，不改写保养记录与工时历史
        mockMvc.perform(post("/api/equipment/eq-main/maintenances/" + maintenanceId + "/appendices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"apx-1","createdBy":"tech-c","content":"锚点工时复核无误，补充说明"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maintenanceId").value(maintenanceId))
                .andExpect(jsonPath("$.createdBy").value("tech-c"));
        mockMvc.perform(get("/api/equipment/eq-main/maintenances/" + maintenanceId + "/appendices"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].content").value("锚点工时复核无误，补充说明"));
        mockMvc.perform(get("/api/equipment/eq-main/maintenances"))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(130));

        // 下一周期正常运行
        addReading("eq-main", "ar-3", 6, "r3", "2026-01-01T12:00:00Z", 180);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(50))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    // ---------- 提交校验：任一失败整次回滚，查询不到半成品 ----------

    @Test
    void submitEvidence_validationFailuresRollbackAtomically() throws Exception {
        createTemplate("tpl-val", "ct-val", """
                [{"itemCode":"oil","itemName":"机油更换","required":true,"sortOrder":1}]
                """);
        registerMaterial("BATCH-OK");
        registerMaterial("BATCH-REV");
        mockMvc.perform(post("/api/material-batches/BATCH-REV/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-1","reason":"质检不合格"}
                                """))
                .andExpect(status().isCreated());
        registerEquipment("eq-val", 1000, "tpl-val");

        // 检查项不属于当前模板 → 422
        MvcResult notInTemplate = submitEvidence("eq-val", "fail-1", 1, "tech-a",
                "[{\"itemCode\":\"unknown\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-OK\",\"summary\":\"机油\"}]");
        assertEquals(422, notInTemplate.getResponse().getStatus());
        assertEquals("EVIDENCE_ITEM_NOT_IN_TEMPLATE",
                JsonPath.read(notInTemplate.getResponse().getContentAsString(), "$.code"));

        // 同一检查项重复 → 422
        MvcResult dupItem = submitEvidence("eq-val", "fail-2", 1, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"},{\"itemCode\":\"oil\",\"result\":\"FAIL\"}]",
                "[{\"batchCode\":\"BATCH-OK\",\"summary\":\"机油\"}]");
        assertEquals(422, dupItem.getResponse().getStatus());
        assertEquals("EVIDENCE_ITEM_DUPLICATE",
                JsonPath.read(dupItem.getResponse().getContentAsString(), "$.code"));

        // 非法结果值 → 422
        MvcResult badResult = submitEvidence("eq-val", "fail-3", 1, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"MAYBE\"}]",
                "[{\"batchCode\":\"BATCH-OK\",\"summary\":\"机油\"}]");
        assertEquals(422, badResult.getResponse().getStatus());
        assertEquals("EVIDENCE_RESULT_INVALID",
                JsonPath.read(badResult.getResponse().getContentAsString(), "$.code"));

        // 缺少材料摘要 → 422
        MvcResult noMaterial = submitEvidence("eq-val", "fail-4", 1, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]", "[]");
        assertEquals(422, noMaterial.getResponse().getStatus());
        assertEquals("EVIDENCE_MATERIAL_REQUIRED",
                JsonPath.read(noMaterial.getResponse().getContentAsString(), "$.code"));

        // 材料批次未登记 → 422
        MvcResult unknownMaterial = submitEvidence("eq-val", "fail-5", 1, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-NONE\",\"summary\":\"机油\"}]");
        assertEquals(422, unknownMaterial.getResponse().getStatus());
        assertEquals("MATERIAL_NOT_FOUND",
                JsonPath.read(unknownMaterial.getResponse().getContentAsString(), "$.code"));

        // 引用已撤销材料 → 422
        MvcResult revoked = submitEvidence("eq-val", "fail-6", 1, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-REV\",\"summary\":\"机油\"}]");
        assertEquals(422, revoked.getResponse().getStatus());
        assertEquals("MATERIAL_REVOKED",
                JsonPath.read(revoked.getResponse().getContentAsString(), "$.code"));

        // 版本冲突 → 409
        MvcResult versionConflict = submitEvidence("eq-val", "fail-7", 99, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-OK\",\"summary\":\"机油\"}]");
        assertEquals(409, versionConflict.getResponse().getStatus());

        // 全部失败整次回滚：无证据包、无检查项、无材料、版本不变、失败不占幂等键
        mockMvc.perform(get("/api/equipment/eq-val/evidence-packages"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-val/status"))
                .andExpect(jsonPath("$.version").value(1));
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_request WHERE request_id LIKE 'fail-%'",
                Integer.class);
        assertEquals(0, idemCount, "失败请求不得占用幂等键");

        // 失败请求复用同一 requestId 修正参数后可成功
        MvcResult fixedAfterFail = submitEvidence("eq-val", "fail-1", 1, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-OK\",\"summary\":\"机油\"}]");
        assertEquals(201, fixedAfterFail.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-val/evidence-packages"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 同一设备周期材料批次不可重复（跨证据包版本）
        MvcResult reused = submitEvidence("eq-val", "ok-2", 2, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-OK\",\"summary\":\"再次使用\"}]");
        assertEquals(422, reused.getResponse().getStatus());
        assertEquals("EVIDENCE_MATERIAL_ALREADY_USED",
                JsonPath.read(reused.getResponse().getContentAsString(), "$.code"));

        // 数据库唯一约束兜底：同周期同批次直接插入违反唯一约束
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO evidence_material (package_id, equipment_id, cycle_no, batch_code,"
                        + " material_name, quantity, unit, summary)"
                        + " VALUES (999999, 'eq-val', 1, 'BATCH-OK', '机油', NULL, NULL, 'x')"));
    }

    // ---------- 模板与材料端点 ----------

    @Test
    void templateAndMaterial_endpointsAndFailures() throws Exception {
        // 创建模板：检查项按规范排序返回
        mockMvc.perform(post("/api/maintenance-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ct-1","templateId":"tpl-t",
                                 "items":[{"itemCode":"filter","itemName":"滤芯","required":true,"sortOrder":2},
                                          {"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.items[0].itemCode").value("oil"))
                .andExpect(jsonPath("$.items[1].itemCode").value("filter"));

        // 重复创建 → 409
        mockMvc.perform(post("/api/maintenance-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ct-2","templateId":"tpl-t",
                                 "items":[{"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_EXISTS"));

        // 检查项编码重复 → 422
        mockMvc.perform(post("/api/maintenance-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ct-3","templateId":"tpl-dup",
                                 "items":[{"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1},
                                          {"itemCode":"oil","itemName":"机油2","required":false,"sortOrder":2}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TEMPLATE_ITEM_DUPLICATE"));

        // 修订模板 → 版本加一，历史版本不改写
        mockMvc.perform(post("/api/maintenance-templates/tpl-t/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rt-1",
                                 "items":[{"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1},
                                          {"itemCode":"filter","itemName":"滤芯","required":true,"sortOrder":2},
                                          {"itemCode":"belt","itemName":"皮带","required":false,"sortOrder":3}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateVersion").value(2))
                .andExpect(jsonPath("$.items", hasSize(3)));
        mockMvc.perform(get("/api/maintenance-templates/tpl-t"))
                .andExpect(jsonPath("$.templateVersion").value(2))
                .andExpect(jsonPath("$.items", hasSize(3)));

        // 修订不存在的模板 → 404
        mockMvc.perform(post("/api/maintenance-templates/tpl-none/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rt-2",
                                 "items":[{"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1}]}
                                """))
                .andExpect(status().isNotFound());

        // 材料批次编码格式非法 → 422
        mockMvc.perform(post("/api/material-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rm-bad","batchCode":"x","materialName":"机油"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_BAD_FORMAT"));

        // 登记 → ACTIVE；重复登记 → 409
        registerMaterial("BATCH-T1");
        mockMvc.perform(get("/api/material-batches/BATCH-T1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.revokedAt").value(nullValue()));
        mockMvc.perform(post("/api/material-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rm-dup","batchCode":"BATCH-T1","materialName":"机油"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATERIAL_EXISTS"));

        // 撤销 → REVOKED；重复撤销 → 409；撤销不存在 → 404
        mockMvc.perform(post("/api/material-batches/BATCH-T1/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rvk-1","reason":"过期"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeReason").value("过期"));
        mockMvc.perform(post("/api/material-batches/BATCH-T1/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rvk-2","reason":"再次"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATERIAL_ALREADY_REVOKED"));
        mockMvc.perform(post("/api/material-batches/BATCH-NONE/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rvk-3","reason":"无"}
                                """))
                .andExpect(status().isNotFound());

        // 登记设备引用不存在的模板 → 404
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-bad-tpl","equipmentId":"eq-bad-tpl",
                                 "maintenancePeriodMinutes":100,"templateId":"tpl-none"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));

        // 模板创建幂等：同键同参重放，同键异参 409
        MvcResult first = postJson("/api/material-batches", """
                {"requestId":"rm-idem","batchCode":"BATCH-T2","materialName":"机油"}
                """);
        assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = postJson("/api/material-batches", """
                {"requestId":"rm-idem","batchCode":"BATCH-T2","materialName":"机油"}
                """);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(post("/api/material-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rm-idem","batchCode":"BATCH-T2","materialName":"改名"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 门禁：OVERDUE 与 FAIL 明细 ----------

    @Test
    void completionGate_overdueAndFailDetails() throws Exception {
        createTemplate("tpl-g", "ct-g", """
                [{"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1}]
                """);
        registerMaterial("BATCH-G1");
        registerMaterial("BATCH-G2");

        // OVERDUE：本轮运行分钟达到周期 2 倍（100*2=200）
        registerEquipment("eq-ov", 100, "tpl-g");
        addReading("eq-ov", "ov-1", 1, "r1", "2026-01-01T10:00:00Z", 250);
        mockMvc.perform(get("/api/equipment/eq-ov/status"))
                .andExpect(jsonPath("$.status").value("OVERDUE"))
                .andExpect(jsonPath("$.minutesUntilDue").value(-150));
        submitEvidence("eq-ov", "ov-2", 2, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-G1\",\"summary\":\"机油\"}]");

        // 既有完成保养：OVERDUE → 422 并返回实际值/要求值/差额
        mockMvc.perform(post("/api/equipment/eq-ov/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ov-3","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_OVERDUE"))
                .andExpect(jsonPath("$.details[0].type").value("EQUIPMENT_OVERDUE"))
                .andExpect(jsonPath("$.details[0].actual").value(250))
                .andExpect(jsonPath("$.details[0].required").value(200))
                .andExpect(jsonPath("$.details[0].difference").value(50));

        // 完工确认同样被 OVERDUE 拦截
        mockMvc.perform(post("/api/equipment/eq-ov/completion-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ov-4","expectedVersion":3,"confirmedBy":"tech-b",
                                 "packageVersion":1,"templateVersion":1,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"))
                .andExpect(jsonPath("$.details[0].type").value("EQUIPMENT_OVERDUE"));

        // 存在 FAIL：422 并列出失败项
        registerEquipment("eq-fl", 100, "tpl-g");
        addReading("eq-fl", "fl-1", 1, "r1", "2026-01-01T10:00:00Z", 50);
        submitEvidence("eq-fl", "fl-2", 2, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"FAIL\",\"note\":\"异常\"}]",
                "[{\"batchCode\":\"BATCH-G2\",\"summary\":\"机油\"}]");
        mockMvc.perform(post("/api/equipment/eq-fl/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fl-3","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"))
                .andExpect(jsonPath("$.details[0].type").value("FAILED_ITEM"))
                .andExpect(jsonPath("$.details[0].target").value("oil"));
        mockMvc.perform(post("/api/equipment/eq-fl/completion-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fl-4","expectedVersion":3,"confirmedBy":"tech-b",
                                 "packageVersion":1,"templateVersion":1,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_READY"))
                .andExpect(jsonPath("$.details[0].type").value("FAILED_ITEM"));
    }

    // ---------- 模板修订：确认时版本变化 409，旧周期证据不改写 ----------

    @Test
    void confirmation_templateVersionConflictAndOldEvidenceImmutable() throws Exception {
        createTemplate("tpl-c", "ct-c", """
                [{"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1}]
                """);
        registerMaterial("BATCH-C1");
        registerMaterial("BATCH-C2");
        registerEquipment("eq-c", 1000, "tpl-c");
        addReading("eq-c", "c-1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        submitEvidence("eq-c", "c-2", 2, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-C1\",\"summary\":\"机油\"}]");

        // 模板修订生成版本 2
        mockMvc.perform(post("/api/maintenance-templates/tpl-c/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rt-c",
                                 "items":[{"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1},
                                          {"itemCode":"filter","itemName":"滤芯","required":true,"sortOrder":2}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateVersion").value(2));

        // 确认时模板版本已变化：无论请求旧版本还是新版本均 409（证据包固化版本为 1）
        mockMvc.perform(post("/api/equipment/eq-c/completion-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-3","expectedVersion":3,"confirmedBy":"tech-b",
                                 "packageVersion":1,"templateVersion":1,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_VERSION_CONFLICT"));
        mockMvc.perform(post("/api/equipment/eq-c/completion-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-4","expectedVersion":3,"confirmedBy":"tech-b",
                                 "packageVersion":1,"templateVersion":2,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMPLATE_VERSION_CONFLICT"));

        // 按新模板版本重新提交（v2），再由另一角色确认
        mockMvc.perform(post("/api/equipment/eq-c/evidence-packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-5","expectedVersion":3,"submittedBy":"tech-a",
                                 "itemResults":[{"itemCode":"oil","result":"PASS"},
                                                {"itemCode":"filter","result":"PASS"}],
                                 "materials":[{"batchCode":"BATCH-C2","summary":"滤芯"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageVersion").value(2))
                .andExpect(jsonPath("$.templateVersion").value(2));
        mockMvc.perform(post("/api/equipment/eq-c/completion-confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-6","expectedVersion":4,"confirmedBy":"tech-b",
                                 "packageVersion":2,"templateVersion":2,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateVersion").value(2))
                .andExpect(jsonPath("$.nextCycleNo").value(2));

        // 旧周期证据保持提交时快照：模板版本 1、仅 oil 一项，不被模板修订改写
        mockMvc.perform(get("/api/equipment/eq-c/evidence-packages/1/1"))
                .andExpect(jsonPath("$.templateVersion").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].itemCode").value("oil"));
    }

    // ---------- 幂等：证据提交与完工确认 ----------

    @Test
    void idempotency_submitAndConfirm() throws Exception {
        createTemplate("tpl-i", "ct-i", """
                [{"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1}]
                """);
        registerMaterial("BATCH-I1");
        registerMaterial("BATCH-I2");
        registerEquipment("eq-i", 1000, "tpl-i");
        addReading("eq-i", "i-1", 1, "r1", "2026-01-01T10:00:00Z", 100);

        // 提交幂等：同键同参重放，业务效果一次
        MvcResult first = submitEvidence("eq-i", "idem-s1", 2, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-I1\",\"summary\":\"机油\"}]");
        assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = submitEvidence("eq-i", "idem-s1", 2, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-I1\",\"summary\":\"机油\"}]");
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-i/evidence-packages"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-i/status"))
                .andExpect(jsonPath("$.version").value(3));

        // 同键异参 → 409
        MvcResult mismatch = submitEvidence("eq-i", "idem-s1", 2, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\",\"note\":\"改\"}]",
                "[{\"batchCode\":\"BATCH-I1\",\"summary\":\"机油\"}]");
        assertEquals(409, mismatch.getResponse().getStatus());
        assertEquals("REQUEST_ID_CONFLICT",
                JsonPath.read(mismatch.getResponse().getContentAsString(), "$.code"));

        // 失败不占键：422 后同 requestId 修正参数成功
        MvcResult failed = submitEvidence("eq-i", "idem-s2", 3, "tech-a",
                "[{\"itemCode\":\"unknown\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-I2\",\"summary\":\"机油\"}]");
        assertEquals(422, failed.getResponse().getStatus());
        MvcResult fixed = submitEvidence("eq-i", "idem-s2", 3, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-I2\",\"summary\":\"机油\"}]");
        assertEquals(201, fixed.getResponse().getStatus());

        // 完工确认幂等：同键同参重放，确认记录仅一条
        MvcResult confirmed = confirm("eq-i", "idem-c1", 4, "tech-b", 2, 1, "r1", 1);
        assertEquals(201, confirmed.getResponse().getStatus());
        MvcResult confirmedReplay = confirm("eq-i", "idem-c1", 4, "tech-b", 2, 1, "r1", 1);
        assertEquals(201, confirmedReplay.getResponse().getStatus());
        assertEquals(confirmed.getResponse().getContentAsString(),
                confirmedReplay.getResponse().getContentAsString());
        Integer confirmations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM completion_confirmation WHERE equipment_id = 'eq-i'",
                Integer.class);
        assertEquals(1, confirmations);
        mockMvc.perform(get("/api/equipment/eq-i/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 周期已推进：旧版本号再次确认 → 409 版本冲突
        MvcResult stale = confirm("eq-i", "idem-c2", 4, "tech-c", 2, 1, "r1", 1);
        assertEquals(409, stale.getResponse().getStatus());
        assertEquals("VERSION_CONFLICT",
                JsonPath.read(stale.getResponse().getContentAsString(), "$.code"));
    }

    // ---------- 更正附录与历史不可变 ----------

    @Test
    void appendix_appendOnlyAndHistoryImmutable() throws Exception {
        createTemplate("tpl-ap", "ct-ap", """
                [{"itemCode":"oil","itemName":"机油","required":true,"sortOrder":1}]
                """);
        registerMaterial("BATCH-A1");
        registerEquipment("eq-ap", 100, "tpl-ap");
        addReading("eq-ap", "ap-1", 1, "r1", "2026-01-01T10:00:00Z", 150);
        submitEvidence("eq-ap", "ap-2", 2, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-A1\",\"summary\":\"机油\"}]");
        MvcResult confirmed = confirm("eq-ap", "ap-3", 3, "tech-b", 1, 1, "r1", 1);
        assertEquals(201, confirmed.getResponse().getStatus());
        long maintenanceId = ((Number) JsonPath.read(confirmed.getResponse().getContentAsString(),
                "$.maintenanceId")).longValue();

        // 创建附录：同键重放
        MvcResult appendix = postJson("/api/equipment/eq-ap/maintenances/" + maintenanceId
                + "/appendices", """
                {"requestId":"apx-1","createdBy":"tech-c","content":"完工后复核：锚点读数无误"}
                """);
        assertEquals(201, appendix.getResponse().getStatus());
        MvcResult appendixReplay = postJson("/api/equipment/eq-ap/maintenances/" + maintenanceId
                + "/appendices", """
                {"requestId":"apx-1","createdBy":"tech-c","content":"完工后复核：锚点读数无误"}
                """);
        assertEquals(201, appendixReplay.getResponse().getStatus());
        assertEquals(appendix.getResponse().getContentAsString(),
                appendixReplay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-ap/maintenances/" + maintenanceId + "/appendices"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 附录不重开周期、不改写历史：保养锚点、读数、周期序号均不变
        mockMvc.perform(get("/api/equipment/eq-ap/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(150));
        mockMvc.perform(get("/api/equipment/eq-ap/readings"))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(150))
                .andExpect(jsonPath("$[0].revisionNo").value(1));
        mockMvc.perform(get("/api/equipment/eq-ap/evidence-diagnostics"))
                .andExpect(jsonPath("$.cycleNo").value(2));

        // 不存在的保养记录/设备 → 404
        mockMvc.perform(post("/api/equipment/eq-ap/maintenances/99999/appendices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"apx-2","createdBy":"tech-c","content":"x"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MAINTENANCE_NOT_FOUND"));
        mockMvc.perform(post("/api/equipment/eq-none/maintenances/1/appendices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"apx-3","createdBy":"tech-c","content":"x"}
                                """))
                .andExpect(status().isNotFound());
    }

    // ---------- 诊断只读与无模板设备 ----------

    @Test
    void diagnostics_readOnlyAndEquipmentWithoutTemplate() throws Exception {
        registerEquipment("eq-plain", 100, null);

        // 无模板设备：诊断返回空证据视图，读取不改变状态
        mockMvc.perform(get("/api/equipment/eq-plain/evidence-diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateId").value(nullValue()))
                .andExpect(jsonPath("$.currentPackageVersion").value(nullValue()))
                .andExpect(jsonPath("$.requiredItemCount").value(0))
                .andExpect(jsonPath("$.readyForCompletion").value(false))
                .andExpect(jsonPath("$.status").value("OK"));
        mockMvc.perform(get("/api/equipment/eq-plain/evidence-diagnostics"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/equipment/eq-plain/status"))
                .andExpect(jsonPath("$.version").value(1));

        // 无模板设备不能提交证据/双人确认
        MvcResult submit = submitEvidence("eq-plain", "np-1", 1, "tech-a",
                "[{\"itemCode\":\"oil\",\"result\":\"PASS\"}]",
                "[{\"batchCode\":\"BATCH-X\",\"summary\":\"x\"}]");
        assertEquals(422, submit.getResponse().getStatus());
        assertEquals("EQUIPMENT_TEMPLATE_REQUIRED",
                JsonPath.read(submit.getResponse().getContentAsString(), "$.code"));
        MvcResult confirmResult = confirm("eq-plain", "np-2", 1, "tech-b", 1, 1, "r1", 1);
        assertEquals(422, confirmResult.getResponse().getStatus());
        assertEquals("EQUIPMENT_TEMPLATE_REQUIRED",
                JsonPath.read(confirmResult.getResponse().getContentAsString(), "$.code"));

        // 历史查询只读且为空
        mockMvc.perform(get("/api/equipment/eq-plain/evidence-packages"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-none/evidence-diagnostics"))
                .andExpect(status().isNotFound());
    }
}
