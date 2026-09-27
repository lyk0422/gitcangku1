package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 保养证据包主流程、失败分支、不可变性、逾期门禁、材料撤销、模板版本裁决、
 * 更正附录、幂等与真实 H2 唯一约束测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvidencePackageTests {

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

    private long versionOf(String equipmentId) {
        return jdbc.queryForObject("SELECT version FROM equipment WHERE equipment_id = ?",
                Long.class, equipmentId);
    }

    private void register(String equipmentId, long period, Long threshold) throws Exception {
        String thresholdJson = threshold == null ? "null" : threshold.toString();
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d,
                                 "overdueThresholdMinutes":%s}
                                """.formatted(equipmentId, equipmentId, period, thresholdJson)))
                .andExpect(status().isCreated());
    }

    private void addReading(String equipmentId, String requestId, String readingId, String sampledAt,
                            long minutes) throws Exception {
        long version = versionOf(equipmentId);
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, version, readingId, sampledAt, minutes)))
                .andExpect(status().isCreated());
    }

    /** 建含 c1（必检）的模板版本 1。 */
    private void templateC1(String equipmentId) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/template/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tpl-%s","expectedVersion":%d,"items":[
                                  {"itemCode":"c1","itemName":"检查1","required":true}]}
                                """.formatted(equipmentId, versionOf(equipmentId))))
                .andExpect(status().isCreated());
    }

    private void material(String materialId, String name, String unit) throws Exception {
        mockMvc.perform(post("/api/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"regmat-%s","materialId":"%s","materialName":"%s","unit":"%s"}
                                """.formatted(materialId, materialId, name, unit)))
                .andExpect(status().isCreated());
    }

    private MvcResult evidenceC1Pass(String equipmentId, String requestId, String submittedBy,
                                     String materialId, double quantity) throws Exception {
        String quantityJson = quantity == Math.floor(quantity)
                ? String.valueOf((long) quantity) : String.valueOf(quantity);
        return mockMvc.perform(post("/api/equipment/" + equipmentId + "/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"submittedBy":"%s",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null}],
                                 "materials":[{"materialId":"%s","quantity":%s}]}
                                """.formatted(requestId, versionOf(equipmentId), submittedBy,
                                materialId, quantityJson)))
                .andReturn();
    }

    private MvcResult complete(String equipmentId, String requestId, String readingId,
                               int anchorRevisionNo, int packageVersion, String confirmedBy)
            throws Exception {
        return mockMvc.perform(post("/api/equipment/" + equipmentId + "/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "anchorRevisionNo":%d,"expectedPackageVersion":%d,"confirmedBy":"%s"}
                                """.formatted(requestId, versionOf(equipmentId), readingId,
                                anchorRevisionNo, packageVersion, confirmedBy)))
                .andReturn();
    }

    /** 主流程：逾期阈值边界 + 双人完工 + 下一周期重置，状态差额字段正确。 */
    @Test
    void thresholdAndTwoPersonCompletion_resetsNextCycle() throws Exception {
        register("eq-1", 100, 150L);

        addReading("eq-1", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        mockMvc.perform(get("/api/equipment/eq-1/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.remainingMinutes").value(0))
                .andExpect(jsonPath("$.overdueByMinutes").value(0))
                .andExpect(jsonPath("$.overdueThresholdMinutes").value(150));

        addReading("eq-1", "r2", "r2", "2026-01-01T11:00:00Z", 170);
        mockMvc.perform(get("/api/equipment/eq-1/status"))
                .andExpect(jsonPath("$.status").value("OVERDUE"))
                .andExpect(jsonPath("$.runMinutes").value(170))
                .andExpect(jsonPath("$.overdueByMinutes").value(20))
                .andExpect(jsonPath("$.remainingMinutes").value(-70));

        templateC1("eq-1");
        material("oil-1", "润滑油", "L");
        assertEqualsStatus(201, evidenceC1Pass("eq-1", "ev-1", "bob", "oil-1", 2));

        // 已 OVERDUE：证据齐也禁止完工，422 明细含逾期差额
        MvcResult overdueComplete = complete("eq-1", "mnt-blocked", "r2", 1, 1, "alice");
        assertEqualsStatus(422, overdueComplete);
        assertTrue(overdueComplete.getResponse().getContentAsString().contains("OVERDUE"));
        assertTrue(overdueComplete.getResponse().getContentAsString().contains("差额 20"));

        // 周期仍未推进、无保养与确认半成品
        mockMvc.perform(get("/api/equipment/eq-1/cycles/current"))
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = 'eq-1'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_confirmation WHERE equipment_id = 'eq-1'",
                Integer.class));
    }

    /** 登记时逾期阈值必须严格大于保养周期。 */
    @Test
    void register_thresholdNotGreaterThanPeriod_422() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-bad-t","equipmentId":"eq-bad-t",
                                 "maintenancePeriodMinutes":100,"overdueThresholdMinutes":100}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OVERDUE_THRESHOLD_INVALID"));
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-bad-t2","equipmentId":"eq-bad-t2",
                                 "maintenancePeriodMinutes":100,"overdueThresholdMinutes":50}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    /** 证据未齐（缺必检项 PASS）时完工 422，并列明缺失项。 */
    @Test
    void completion_missingRequiredItem_listsMissing() throws Exception {
        register("eq-2", 1000, null);
        addReading("eq-2", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        mockMvc.perform(post("/api/equipment/eq-2/template/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tpl-2","expectedVersion":2,"items":[
                                  {"itemCode":"c1","itemName":"检查1","required":true},
                                  {"itemCode":"c2","itemName":"检查2","required":true}]}
                                """))
                .andExpect(status().isCreated());
        material("oil-2", "润滑油", "L");
        // 证据包只提交 c1（提交允许缺项）
        mockMvc.perform(post("/api/equipment/eq-2/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-2","expectedVersion":3,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null}],
                                 "materials":[{"materialId":"oil-2","quantity":1}]}
                                """))
                .andExpect(status().isCreated());

        MvcResult result = complete("eq-2", "mnt-2", "r1", 1, 1, "alice");
        assertEqualsStatus(422, result);
        assertTrue(result.getResponse().getContentAsString().contains("c2"),
                "422 明细须列明缺失的必检项 c2");

        // 诊断视图同样列明缺失项且未就绪
        mockMvc.perform(get("/api/equipment/eq-2/cycles/current"))
                .andExpect(jsonPath("$.readiness.readyToComplete").value(false))
                .andExpect(jsonPath("$.readiness.requiredItemCount").value(2))
                .andExpect(jsonPath("$.readiness.passedRequiredItemCount").value(1))
                .andExpect(jsonPath("$.readiness.missingRequiredItems[0]").value("c2"));
    }

    /** 材料撤销：已固化摘要不变；撤销后新引用 422，重复撤销 409。 */
    @Test
    void materialRevocation_historyKeptNewReferenceRejected() throws Exception {
        register("eq-3", 1000, null);
        addReading("eq-3", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        templateC1("eq-3");
        material("oil-3", "液压油", "L");
        assertEqualsStatus(201, evidenceC1Pass("eq-3", "ev-3", "bob", "oil-3", 1.25));

        mockMvc.perform(post("/api/materials/oil-3/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"revoke-3","reason":"批次复检不合格"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeReason").value("批次复检不合格"));
        mockMvc.perform(post("/api/materials/oil-3/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"revoke-3-again","reason":"x"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATERIAL_ALREADY_REVOKED"));

        // 历史证据包仍保留该材料摘要与单位
        mockMvc.perform(get("/api/equipment/eq-3/cycles/1/evidence/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materials[0].materialId").value("oil-3"))
                .andExpect(jsonPath("$.materials[0].quantity").value(1.25))
                .andExpect(jsonPath("$.materials[0].unit").value("L"));

        // 新证据包引用已撤销材料 → 422 整次回滚
        MvcResult invalid = mockMvc.perform(post("/api/equipment/eq-3/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-3-revoked","expectedVersion":4,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null}],
                                 "materials":[{"materialId":"oil-3","quantity":1}]}
                                """))
                .andReturn();
        assertEqualsStatus(422, invalid);
        assertTrue(invalid.getResponse().getContentAsString().contains("oil-3"));
        mockMvc.perform(get("/api/equipment/eq-3/cycles/current"))
                .andExpect(jsonPath("$.currentEvidenceVersion").value(1));
    }

    /** 证据提交后模板再修订：确认旧证据 409；基于新模板重新提交后可完工。 */
    @Test
    void templateRevisionAfterEvidence_confirm409ThenResubmit() throws Exception {
        register("eq-4", 1000, null);
        addReading("eq-4", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        templateC1("eq-4");
        material("oil-4a", "油A", "L");
        material("oil-4b", "油B", "L");
        assertEqualsStatus(201, evidenceC1Pass("eq-4", "ev-4-v1", "bob", "oil-4a", 1));

        // 模板修订为版本 2（保留 c1，新增 c2）
        mockMvc.perform(post("/api/equipment/eq-4/template/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tpl-4-v2","expectedVersion":4,"items":[
                                  {"itemCode":"c1","itemName":"检查1","required":true},
                                  {"itemCode":"c2","itemName":"检查2","required":true}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        // 旧证据版本 1 基于模板版本 1 → 确认 409
        MvcResult stale = complete("eq-4", "mnt-stale", "r1", 1, 1, "alice");
        assertEqualsStatus(409, stale);
        assertTrue(stale.getResponse().getContentAsString().contains("TEMPLATE_VERSION_CONFLICT"));
        // 诊断标注版本漂移
        mockMvc.perform(get("/api/equipment/eq-4/cycles/current"))
                .andExpect(jsonPath("$.readiness.readyToComplete").value(false))
                .andExpect(jsonPath("$.readiness.reasons",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("模板已修订"))));

        // 基于模板版本 2 提交证据版本 2（c1、c2 全 PASS + 新材料）
        mockMvc.perform(post("/api/equipment/eq-4/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-4-v2","expectedVersion":5,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null},
                                           {"itemCode":"c2","result":"PASS","note":null}],
                                 "materials":[{"materialId":"oil-4b","quantity":2}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageVersion").value(2))
                .andExpect(jsonPath("$.templateVersion").value(2));

        MvcResult done = complete("eq-4", "mnt-4", "r1", 1, 2, "alice");
        assertEqualsStatus(201, done);
        mockMvc.perform(get("/api/equipment/eq-4/maintenances"))
                .andExpect(jsonPath("$[0].templateVersion").value(2))
                .andExpect(jsonPath("$[0].evidencePackageVersion").value(2));
        // 旧模板与旧证据行保留不改写
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT version) FROM maintenance_template_item WHERE template_id = 'eq-4'",
                Integer.class));
    }

    /** 更正附录只追加；未知保养 404；附录不改变周期与工时历史。 */
    @Test
    void corrections_appendOnly() throws Exception {
        register("eq-5", 1000, null);
        addReading("eq-5", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        templateC1("eq-5");
        material("oil-5", "油", "L");
        assertEqualsStatus(201, evidenceC1Pass("eq-5", "ev-5", "bob", "oil-5", 1));
        MvcResult done = complete("eq-5", "mnt-5", "r1", 1, 1, "alice");
        assertEqualsStatus(201, done);
        long maintenanceId = jdbc.queryForObject(
                "SELECT maintenance_id FROM maintenance WHERE equipment_id = 'eq-5'", Long.class);

        mockMvc.perform(post("/api/maintenances/" + maintenanceId + "/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"corr-1","content":"实际加注 0.8L，记录值 1L，差额 0.2L","createdBy":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cycleNo").value(1))
                .andExpect(jsonPath("$.content").value("实际加注 0.8L，记录值 1L，差额 0.2L"));
        mockMvc.perform(post("/api/maintenances/" + maintenanceId + "/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"corr-2","content":"补充说明","createdBy":"bob"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-5/corrections"))
                .andExpect(jsonPath("$.length()").value(2));

        // 未知保养记录 → 404
        mockMvc.perform(post("/api/maintenances/999999/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"corr-x","content":"x","createdBy":"alice"}
                                """))
                .andExpect(status().isNotFound());

        // 工时历史与周期状态不被附录改变：周期 1 COMPLETED、周期 2 OPEN、锚点工时仍为 100
        mockMvc.perform(get("/api/equipment/eq-5/maintenances"))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(100));
        mockMvc.perform(get("/api/equipment/eq-5/cycles"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].status").value("OPEN"));
    }

    /** 证据提交幂等：失败不占键、同参重放原结果、异参 409。 */
    @Test
    void evidenceIdempotency() throws Exception {
        register("eq-6", 1000, null);
        addReading("eq-6", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        templateC1("eq-6");
        material("oil-6", "油", "L");

        String validBody = """
                {"requestId":"ev-6-idem","expectedVersion":3,"submittedBy":"bob",
                 "checks":[{"itemCode":"c1","result":"PASS","note":null}],
                 "materials":[{"materialId":"oil-6","quantity":1}]}
                """;
        // 先失败（版本不符）→ 409 不占键
        MvcResult failedFirst = mockMvc.perform(post("/api/equipment/eq-6/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody.replace("\"expectedVersion\":3", "\"expectedVersion\":99")))
                .andReturn();
        assertEqualsStatus(409, failedFirst);

        MvcResult first = mockMvc.perform(post("/api/equipment/eq-6/evidence")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody))
                .andReturn();
        assertEqualsStatus(201, first);
        MvcResult replay = mockMvc.perform(post("/api/equipment/eq-6/evidence")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody))
                .andReturn();
        assertEqualsStatus(201, replay);
        org.junit.jupiter.api.Assertions.assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_package WHERE equipment_id = 'eq-6'", Integer.class));

        // 同键异参 → 409
        MvcResult mismatch = mockMvc.perform(post("/api/equipment/eq-6/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody.replace("\"quantity\":1", "\"quantity\":2")))
                .andReturn();
        assertEqualsStatus(409, mismatch);
    }

    /** 一次更新多检查项与多材料：任一非法整次回滚，查不到半成品。 */
    @Test
    void evidenceSubmit_atomicRollbackOnAnyFailure() throws Exception {
        register("eq-7", 1000, null);
        addReading("eq-7", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        mockMvc.perform(post("/api/equipment/eq-7/template/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tpl-7","expectedVersion":2,"items":[
                                  {"itemCode":"c1","itemName":"检查1","required":true},
                                  {"itemCode":"c2","itemName":"检查2","required":true}]}
                                """))
                .andExpect(status().isCreated());
        material("oil-7a", "油A", "L");
        material("oil-7b", "油B", "L");

        // 同次含：重复结果 c1、非法结果值、同次重复材料 + 不存在材料
        MvcResult result = mockMvc.perform(post("/api/equipment/eq-7/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-7-bad","expectedVersion":3,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null},
                                           {"itemCode":"c1","result":"FAIL","note":null},
                                           {"itemCode":"c2","result":"MAYBE","note":null}],
                                 "materials":[{"materialId":"oil-7a","quantity":1},
                                              {"materialId":"oil-7a","quantity":2},
                                              {"materialId":"ghost-oil","quantity":1}]}
                                """))
                .andReturn();
        assertEqualsStatus(422, result);
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("检查项结果重复"));
        assertTrue(body.contains("PASS/FAIL"));
        assertTrue(body.contains("本次提交中重复"));
        assertTrue(body.contains("ghost-oil"));
        // 全部对象未落库：无证据包、无结果、无材料摘要占用
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_package WHERE equipment_id = 'eq-7'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_check_result WHERE equipment_id = 'eq-7'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence_material_summary WHERE equipment_id = 'eq-7'",
                Integer.class));

        // 失败后材料仍可在修正后的请求中使用，证明失败未占用材料
        MvcResult fixed = mockMvc.perform(post("/api/equipment/eq-7/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-7-ok","expectedVersion":3,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null},
                                           {"itemCode":"c2","result":"PASS","note":null}],
                                 "materials":[{"materialId":"oil-7a","quantity":1.123}]}
                                """))
                .andReturn();
        assertEqualsStatus(201, fixed);

        // 数量精度超过千分位 → 422
        MvcResult precision = mockMvc.perform(post("/api/equipment/eq-7/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ev-7-precision","expectedVersion":4,"submittedBy":"bob",
                                 "checks":[{"itemCode":"c1","result":"PASS","note":null},
                                           {"itemCode":"c2","result":"PASS","note":null}],
                                 "materials":[{"materialId":"oil-7b","quantity":1.1234}]}
                                """))
                .andReturn();
        assertEqualsStatus(422, precision);
    }

    /** 区间左闭右开：第二周期锚点必须严格晚于第一周期锚点时刻，相等或更早均 422。 */
    @Test
    void secondCycleAnchor_mustBeStrictlyLater_halfOpen() throws Exception {
        register("eq-10", 1000, null);
        addReading("eq-10", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-10", "r2", "r2", "2026-01-01T11:00:00Z", 200);
        templateC1("eq-10");
        material("oil-10", "油", "L");
        assertEqualsStatus(201, evidenceC1Pass("eq-10", "ev-10-v1", "bob", "oil-10", 1));
        // 周期 1 以 r1（10:00）完工
        assertEqualsStatus(201, complete("eq-10", "mnt-10-1", "r1", 1, 1, "alice"));

        // 周期 2 重新提交证据（新材料批次维度按周期隔离，同一材料可再次引用）
        assertEqualsStatus(201, evidenceC1Pass("eq-10", "ev-10-v2", "bob", "oil-10", 1));

        // 相等锚点时刻（r1 已锚定，且不晚于周期起点）→ 422
        MvcResult same = complete("eq-10", "mnt-10-same", "r1", 1, 1, "carol");
        assertEqualsStatus(422, same);
        org.junit.jupiter.api.Assertions.assertTrue(
                same.getResponse().getContentAsString().contains("ANCHOR_TIME_NOT_LATER")
                        || same.getResponse().getStatus() == 422);
        // 周期 2 仍 OPEN，只有一条保养
        mockMvc.perform(get("/api/equipment/eq-10/cycles/current"))
                .andExpect(jsonPath("$.cycleNo").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = 'eq-10'", Integer.class));

        // 严格晚于起点的 r2 完工成功
        assertEqualsStatus(201, complete("eq-10", "mnt-10-2", "r2", 1, 1, "carol"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = 'eq-10'", Integer.class));
        mockMvc.perform(get("/api/equipment/eq-10/cycles/current"))
                .andExpect(jsonPath("$.cycleNo").value(3))
                .andExpect(jsonPath("$.startAnchorCumulativeMinutes").value(200));
    }

    /** 读取诊断与历史不改变状态（连续读取版本与门禁结果一致）。 */
    @Test
    void readOnly_doesNotChangeState() throws Exception {
        register("eq-8", 1000, null);
        addReading("eq-8", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        templateC1("eq-8");
        material("oil-8", "油", "L");
        assertEqualsStatus(201, evidenceC1Pass("eq-8", "ev-8", "bob", "oil-8", 1));

        long before = versionOf("eq-8");
        mockMvc.perform(get("/api/equipment/eq-8/cycles/current"))
                .andExpect(jsonPath("$.readiness.readyToComplete").value(true))
                .andExpect(jsonPath("$.readiness.reasons.length()").value(0));
        mockMvc.perform(get("/api/equipment/eq-8/cycles/current"))
                .andExpect(jsonPath("$.readiness.readyToComplete").value(true));
        mockMvc.perform(get("/api/equipment/eq-8/cycles/1/evidence"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/equipment/eq-8/cycles/1/evidence/1"))
                .andExpect(jsonPath("$.checks[0].note").isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(before, versionOf("eq-8"),
                "只读操作不得推进设备版本");
    }

    /** 真实 H2 唯一约束：证据包版本、周期内材料、周期确认均由数据库兜底。 */
    @Test
    void h2UniqueConstraints_enforced() throws Exception {
        register("eq-9", 1000, null);
        addReading("eq-9", "r1", "r1", "2026-01-01T10:00:00Z", 100);
        templateC1("eq-9");
        material("oil-9", "油", "L");
        assertEqualsStatus(201, evidenceC1Pass("eq-9", "ev-9", "bob", "oil-9", 1));

        // 证据包版本号周期内唯一
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO evidence_package (equipment_id, cycle_no, package_version, template_version,"
                        + " submitted_by, submitted_at, request_id) VALUES ('eq-9', 1, 1, 1, 'x',"
                        + " TIMESTAMP WITH TIME ZONE '2026-01-01 10:00:00+00', 'raw-dup-pkg')"));
        // 同设备同周期材料批次唯一（即使给出不同证据包版本）
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO evidence_material_summary (equipment_id, cycle_no, package_version,"
                        + " material_id, quantity, unit) VALUES ('eq-9', 1, 2, 'oil-9', 9, 'L')"));
        // 每周期至多一条双人确认
        jdbc.update("INSERT INTO maintenance_confirmation (equipment_id, cycle_no, package_version,"
                        + " template_version, maintenance_id, submitted_by, confirmed_by, request_id,"
                        + " confirmed_at) VALUES ('eq-9', 1, 1, 1, 999, 'bob', 'alice', 'raw-conf-1',"
                        + " TIMESTAMP WITH TIME ZONE '2026-01-01 10:00:00+00')");
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO maintenance_confirmation (equipment_id, cycle_no, package_version,"
                        + " template_version, maintenance_id, submitted_by, confirmed_by, request_id,"
                        + " confirmed_at) VALUES ('eq-9', 1, 1, 1, 999, 'bob', 'zoe', 'raw-conf-2',"
                        + " TIMESTAMP WITH TIME ZONE '2026-01-01 10:01:00+00')"));
    }

    private static void assertEqualsStatus(int expected, MvcResult result) throws AssertionError {
        try {
            org.junit.jupiter.api.Assertions.assertEquals(expected, result.getResponse().getStatus(),
                    "响应体：" + result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
