package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
 * 设备能源隔离令 API 主流程与失败分支测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IsolationApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM rejected_reading");
        jdbc.update("DELETE FROM lock_event");
        jdbc.update("DELETE FROM lock_point");
        jdbc.update("DELETE FROM isolation_order");
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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d,"operator":"reader-1"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().is(expectedStatus));
    }

    private MvcResult createIsolation(String equipmentId, String requestId, String orderId,
                                      String lockPointsJson) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/isolations", """
                {"requestId":"%s","orderId":"%s","operator":"creator-1","lockPointIds":%s}
                """.formatted(requestId, orderId, lockPointsJson));
    }

    private MvcResult confirm(String equipmentId, String orderId, String requestId,
                              String role, String operator, String pointIdsJson) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/isolations/" + orderId + "/confirmations",
                """
                {"requestId":"%s","role":"%s","operator":"%s","pointIds":%s}
                """.formatted(requestId, role, operator, pointIdsJson));
    }

    private MvcResult release(String equipmentId, String orderId, String requestId,
                              String releasesJson) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/isolations/" + orderId + "/release",
                """
                {"requestId":"%s","operator":"releaser-1","reason":"作业完成","releases":%s}
                """.formatted(requestId, releasesJson));
    }

    /** 完整走完 创建 → 双角色确认 → 生效。 */
    private void isolateEffectively(String equipmentId, String orderId,
                                    String pointIdsJson) throws Exception {
        createIsolation(equipmentId, "iso-" + orderId, orderId, pointIdsJson);
        confirm(equipmentId, orderId, "cf-e-" + orderId, "ELECTRICIAN", "elec-1", pointIdsJson);
        confirm(equipmentId, orderId, "cf-m-" + orderId, "MECHANIC", "mech-1", pointIdsJson);
    }

    // ---------- 主流程：创建 → 双角色确认 → 生效 → 读数被拒 → 保养可提交 → 逐一解除 → 读数恢复 ----------

    @Test
    void mainFlow_isolateBlockReadingsThenRelease() throws Exception {
        register("eq-iso", 100);
        addReading("eq-iso", "rd-1", 1, "r1", "2026-01-01T10:00:00Z", 50, 201);

        // 创建隔离令：PENDING，锁定点按规范化顺序固化
        mockMvc.perform(post("/api/equipment/eq-iso/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-1","orderId":"ord-1","operator":"creator-1",
                                 "lockPointIds":["LP-B","LP-A"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.effectiveAt").value(nullValue()))
                .andExpect(jsonPath("$.lockPoints[0].pointId").value("LP-A"))
                .andExpect(jsonPath("$.lockPoints[1].pointId").value("LP-B"))
                .andExpect(jsonPath("$.requiredPointCount").value(2))
                .andExpect(jsonPath("$.requiredConfirmationCount").value(4))
                .andExpect(jsonPath("$.confirmationCount").value(0))
                .andExpect(jsonPath("$.remainingConfirmationCount").value(4));

        // PENDING 期间读数仍可写入（尚未生效）
        addReading("eq-iso", "rd-2", 2, "r2", "2026-01-01T11:00:00Z", 80, 201);

        // 第一角色确认：仍未生效
        mockMvc.perform(post("/api/equipment/eq-iso/isolations/ord-1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-1","role":"ELECTRICIAN","operator":"elec-1",
                                 "pointIds":["LP-A","LP-B"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.confirmationCount").value(2))
                .andExpect(jsonPath("$.remainingConfirmationCount").value(2));

        // 第二角色确认：全部锁定点经两名不同角色确认 → 生效
        mockMvc.perform(post("/api/equipment/eq-iso/isolations/ord-1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-2","role":"MECHANIC","operator":"mech-1",
                                 "pointIds":["LP-A","LP-B"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ISOLATED"))
                .andExpect(jsonPath("$.confirmationCount").value(4))
                .andExpect(jsonPath("$.remainingConfirmationCount").value(0))
                .andExpect(jsonPath("$.confirmedPointCount").value(2));

        // 隔离生效后新增运行读数 → 409，且固化被拒证据
        addReading("eq-iso", "rd-3", 3, "r3", "2026-01-01T12:00:00Z", 120, 409);
        mockMvc.perform(get("/api/equipment/eq-iso/rejected-readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value("ord-1"))
                .andExpect(jsonPath("$[0].readingId").value("r3"))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(120))
                .andExpect(jsonPath("$[0].requestedBy").value("reader-1"))
                .andExpect(jsonPath("$[0].requestId").value("rd-3"));

        // 保养检查项可继续提交（不受隔离限制）
        mockMvc.perform(post("/api/equipment/eq-iso/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-iso","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 逐一解除全部锁定点（须写原因）→ RELEASED
        mockMvc.perform(post("/api/equipment/eq-iso/isolations/ord-1/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rel-1","operator":"releaser-1","reason":"检修完成",
                                 "releases":[{"pointId":"LP-A","reason":"电气恢复"},
                                             {"pointId":"LP-B","reason":"机械恢复"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.releasedBy").value("releaser-1"))
                .andExpect(jsonPath("$.releaseReason").value("检修完成"))
                .andExpect(jsonPath("$.releasedPointCount").value(2))
                .andExpect(jsonPath("$.remainingReleaseCount").value(0))
                .andExpect(jsonPath("$.lockPoints[0].releaseReason").value("电气恢复"))
                .andExpect(jsonPath("$.lockPoints[1].releaseReason").value("机械恢复"));

        // 解除后读数恢复可写（设备版本未被隔离流转改变：登记1 + 读数2 + 保养1 = 4）
        addReading("eq-iso", "rd-4", 4, "r4", "2026-01-01T13:00:00Z", 200, 201);

        // 明细：确认与解除证据完整保留，不被后续操作改写
        mockMvc.perform(get("/api/equipment/eq-iso/isolations/ord-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.lockPoints[0].confirmations[0].role").value("ELECTRICIAN"))
                .andExpect(jsonPath("$.lockPoints[0].confirmations[0].operator").value("elec-1"))
                .andExpect(jsonPath("$.lockPoints[0].confirmations[1].role").value("MECHANIC"))
                .andExpect(jsonPath("$.lockPoints[0].confirmations[1].operator").value("mech-1"))
                .andExpect(jsonPath("$.lockPoints[0].releasedBy").value("releaser-1"));

        // 事件流水：4 次确认 + 2 次解除，按发生顺序
        mockMvc.perform(get("/api/isolation-orders/ord-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                .andExpect(jsonPath("$[0].eventType").value("CONFIRM"))
                .andExpect(jsonPath("$[0].role").value("ELECTRICIAN"))
                .andExpect(jsonPath("$[4].eventType").value("RELEASE"))
                .andExpect(jsonPath("$[4].reason").value("电气恢复"))
                .andExpect(jsonPath("$[5].reason").value("机械恢复"));

        // 历史：该设备仅一道令
        mockMvc.perform(get("/api/equipment/eq-iso/isolations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value("ord-1"));
    }

    // ---------- 创建校验 ----------

    @Test
    void create_duplicateLockPoints_422() throws Exception {
        register("eq-dup-lp", 100);
        mockMvc.perform(post("/api/equipment/eq-dup-lp/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-dup","orderId":"ord-dup","operator":"op",
                                 "lockPointIds":["LP-A","LP-A"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_DUPLICATE"));
        // 未创建任何隔离令
        mockMvc.perform(get("/api/equipment/eq-dup-lp/isolations"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void create_onDecommissionedEquipment_422() throws Exception {
        register("eq-dec", 100);
        // 停用设备
        mockMvc.perform(post("/api/equipment/eq-dec/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dec-1","expectedVersion":1,"operator":"admin-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECOMMISSIONED"))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(post("/api/equipment/eq-dec/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-dec","orderId":"ord-dec","operator":"op",
                                 "lockPointIds":["LP-A"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_DECOMMISSIONED"));
    }

    @Test
    void create_secondOpenOrder_422() throws Exception {
        register("eq-open", 100);
        createIsolation("eq-open", "iso-o1", "ord-o1", "[\"LP-A\"]");
        mockMvc.perform(post("/api/equipment/eq-open/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-o2","orderId":"ord-o2","operator":"op",
                                 "lockPointIds":["LP-B"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPEN_ISOLATION_EXISTS"));
    }

    // ---------- 确认校验 ----------

    @Test
    void confirm_sameRoleTwice_422() throws Exception {
        register("eq-role", 100);
        createIsolation("eq-role", "iso-r", "ord-r", "[\"LP-A\",\"LP-B\"]");
        confirm("eq-role", "ord-r", "cf-r1", "ELECTRICIAN", "elec-1", "[\"LP-A\",\"LP-B\"]");
        // 同一角色再次确认（即便不同操作人）→ 422
        mockMvc.perform(post("/api/equipment/eq-role/isolations/ord-r/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-r2","role":"ELECTRICIAN","operator":"elec-2",
                                 "pointIds":["LP-A","LP-B"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_ALREADY_CONFIRMED"));
        // 事件流水中仍只有第一次的两条确认
        mockMvc.perform(get("/api/isolation-orders/ord-r/events"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void confirm_partialPointsAllowedButNotEffective() throws Exception {
        register("eq-part", 100);
        createIsolation("eq-part", "iso-p", "ord-p", "[\"LP-A\",\"LP-B\"]");
        // 两名角色可分批确认；全部点均双确认前不生效
        confirm("eq-part", "ord-p", "cf-p1", "ELECTRICIAN", "elec-1", "[\"LP-A\"]");
        confirm("eq-part", "ord-p", "cf-p2", "MECHANIC", "mech-1", "[\"LP-A\"]");
        confirm("eq-part", "ord-p", "cf-p3", "ELECTRICIAN", "elec-1", "[\"LP-B\"]");
        mockMvc.perform(get("/api/equipment/eq-part/isolations/ord-p"))
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.remainingConfirmationCount").value(1));
        confirm("eq-part", "ord-p", "cf-p4", "MECHANIC", "mech-1", "[\"LP-B\"]");
        mockMvc.perform(get("/api/equipment/eq-part/isolations/ord-p"))
                .andExpect(jsonPath("$.state").value("ISOLATED"));
    }

    @Test
    void confirm_unknownPointAndBadRole_rejected() throws Exception {
        register("eq-unk", 100);
        createIsolation("eq-unk", "iso-u", "ord-u", "[\"LP-A\"]");
        mockMvc.perform(post("/api/equipment/eq-unk/isolations/ord-u/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-u1","role":"ELECTRICIAN","operator":"e1",
                                 "pointIds":["LP-X"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_NOT_FOUND"));
        // 非法角色 → 400
        mockMvc.perform(post("/api/equipment/eq-unk/isolations/ord-u/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-u2","role":"PLUMBER","operator":"e1","pointIds":["LP-A"]}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ---------- 解除校验 ----------

    @Test
    void release_partialRelease_422_noPartialState() throws Exception {
        register("eq-pre", 100);
        isolateEffectively("eq-pre", "ord-pre", "[\"LP-A\",\"LP-B\"]");

        // 只解除部分锁定点 → 422，且不产生任何解除变更
        mockMvc.perform(post("/api/equipment/eq-pre/isolations/ord-pre/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rel-p1","operator":"op","reason":"部分解除",
                                 "releases":[{"pointId":"LP-A","reason":"仅A"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INCOMPLETE_RELEASE"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("实际提交 1 个，要求 2 个，差额 1 个")));

        mockMvc.perform(get("/api/equipment/eq-pre/isolations/ord-pre"))
                .andExpect(jsonPath("$.state").value("ISOLATED"))
                .andExpect(jsonPath("$.releasedPointCount").value(0))
                .andExpect(jsonPath("$.lockPoints[0].releasedAt").value(nullValue()))
                .andExpect(jsonPath("$.lockPoints[1].releasedAt").value(nullValue()));
        // 无解除事件写入
        Integer releaseEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lock_event WHERE order_id = 'ord-pre' AND event_type = 'RELEASE'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, releaseEvents);
    }

    @Test
    void release_beforeEffective_422() throws Exception {
        register("eq-neff", 100);
        createIsolation("eq-neff", "iso-n", "ord-n", "[\"LP-A\"]");
        mockMvc.perform(post("/api/equipment/eq-neff/isolations/ord-n/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rel-n1","operator":"op","reason":"提前解除",
                                 "releases":[{"pointId":"LP-A","reason":"r"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_EFFECTIVE"));
    }

    @Test
    void release_unknownPoint_422_andReRelease_422() throws Exception {
        register("eq-re2", 100);
        isolateEffectively("eq-re2", "ord-re2", "[\"LP-A\"]");
        // 未知锁定点
        mockMvc.perform(post("/api/equipment/eq-re2/isolations/ord-re2/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rel-x","operator":"op","reason":"r",
                                 "releases":[{"pointId":"LP-Z","reason":"r"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_NOT_FOUND"));
        // 正常解除
        release("eq-re2", "ord-re2", "rel-ok", "[{\"pointId\":\"LP-A\",\"reason\":\"完成\"}]");
        // 重复解除 → 422
        mockMvc.perform(post("/api/equipment/eq-re2/isolations/ord-re2/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rel-again","operator":"op","reason":"r",
                                 "releases":[{"pointId":"LP-A","reason":"r"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_RELEASED"));
    }

    // ---------- 批量隔离 ----------

    @Test
    void batch_allOrNothing() throws Exception {
        register("eq-b1", 100);
        register("eq-b2", 100);
        register("eq-b3", 100);
        // eq-b3 已停用
        mockMvc.perform(post("/api/equipment/eq-b3/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dec-b3","expectedVersion":1,"operator":"admin"}
                                """))
                .andExpect(status().isOk());

        // 任一设备失败（已停用）→ 整批不生效
        mockMvc.perform(post("/api/equipment/isolations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-1","operator":"op",
                                 "orders":[{"equipmentId":"eq-b1","orderId":"bo-1","lockPointIds":["LP-1"]},
                                           {"equipmentId":"eq-b3","orderId":"bo-3","lockPointIds":["LP-3"]}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_DECOMMISSIONED"));
        mockMvc.perform(get("/api/equipment/eq-b1/isolations"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 批内锁定点重复 → 整批 422
        mockMvc.perform(post("/api/equipment/isolations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-2","operator":"op",
                                 "orders":[{"equipmentId":"eq-b1","orderId":"bo-1","lockPointIds":["LP-1"]},
                                           {"equipmentId":"eq-b2","orderId":"bo-2","lockPointIds":["LP-X","LP-X"]}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_DUPLICATE"));
        mockMvc.perform(get("/api/equipment/eq-b1/isolations"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 全部合法 → 整批生效，响应含实际/要求数量
        mockMvc.perform(post("/api/equipment/isolations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-3","operator":"op",
                                 "orders":[{"equipmentId":"eq-b2","orderId":"bo-2","lockPointIds":["LP-2"]},
                                           {"equipmentId":"eq-b1","orderId":"bo-1","lockPointIds":["LP-1"]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdCount").value(2))
                .andExpect(jsonPath("$.requiredCount").value(2))
                .andExpect(jsonPath("$.orders[0].equipmentId").value("eq-b1"))
                .andExpect(jsonPath("$.orders[1].equipmentId").value("eq-b2"));
        mockMvc.perform(get("/api/equipment/eq-b1/isolations"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-b2/isolations"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- 幂等 ----------

    @Test
    void idempotency_isolationOperations() throws Exception {
        register("eq-idm", 100);

        // 创建：同键同参重放返回首次快照
        MvcResult first = createIsolation("eq-idm", "idem-c", "ord-idm", "[\"LP-A\",\"LP-B\"]");
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = createIsolation("eq-idm", "idem-c", "ord-idm", "[\"LP-A\",\"LP-B\"]");
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-idm/isolations"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-idm/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-c","orderId":"ord-idm","operator":"op",
                                 "lockPointIds":["LP-A","LP-C"]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 确认：同键同参重放，事件不重复
        confirm("eq-idm", "ord-idm", "idem-cf", "ELECTRICIAN", "e1", "[\"LP-A\",\"LP-B\"]");
        MvcResult cfReplay = confirm("eq-idm", "ord-idm", "idem-cf", "ELECTRICIAN", "e1",
                "[\"LP-A\",\"LP-B\"]");
        org.junit.jupiter.api.Assertions.assertEquals(201, cfReplay.getResponse().getStatus());
        mockMvc.perform(get("/api/isolation-orders/ord-idm/events"))
                .andExpect(jsonPath("$", hasSize(2)));

        // 失败不占键：422 后同 requestId 修正参数可成功
        mockMvc.perform(post("/api/equipment/eq-idm/isolations/ord-idm/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-cf2","role":"MECHANIC","operator":"m1",
                                 "pointIds":["LP-X"]}
                                """))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/equipment/eq-idm/isolations/ord-idm/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-cf2","role":"MECHANIC","operator":"m1",
                                 "pointIds":["LP-A","LP-B"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ISOLATED"));

        // 解除：同键重放返回首次快照
        MvcResult relFirst = release("eq-idm", "ord-idm", "idem-rel",
                "[{\"pointId\":\"LP-A\",\"reason\":\"a\"},{\"pointId\":\"LP-B\",\"reason\":\"b\"}]");
        org.junit.jupiter.api.Assertions.assertEquals(200, relFirst.getResponse().getStatus());
        MvcResult relReplay = release("eq-idm", "ord-idm", "idem-rel",
                "[{\"pointId\":\"LP-A\",\"reason\":\"a\"},{\"pointId\":\"LP-B\",\"reason\":\"b\"}]");
        org.junit.jupiter.api.Assertions.assertEquals(200, relReplay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                relFirst.getResponse().getContentAsString(),
                relReplay.getResponse().getContentAsString());
        // 解除事件仅两条（重放不产生新事件）
        Integer releaseEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lock_event WHERE order_id = 'ord-idm' AND event_type = 'RELEASE'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(2, releaseEvents);
    }

    // ---------- 历史稳定性与查询 ----------

    @Test
    void history_evidenceStableAfterSubsequentOperations() throws Exception {
        register("eq-hist", 100);
        isolateEffectively("eq-hist", "ord-h1", "[\"LP-A\"]");
        addReading("eq-hist", "rd-h1", 1, "rh1", "2026-01-01T10:00:00Z", 10, 409);
        release("eq-hist", "ord-h1", "rel-h1", "[{\"pointId\":\"LP-A\",\"reason\":\"done\"}]");

        // 解除后再建一道新令并生效，旧令证据不变
        isolateEffectively("eq-hist", "ord-h2", "[\"LP-Z\"]");
        mockMvc.perform(get("/api/equipment/eq-hist/isolations"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].orderId").value("ord-h1"))
                .andExpect(jsonPath("$[0].state").value("RELEASED"))
                .andExpect(jsonPath("$[0].lockPoints[0].confirmations", hasSize(2)))
                .andExpect(jsonPath("$[0].lockPoints[0].releaseReason").value("done"))
                .andExpect(jsonPath("$[1].orderId").value("ord-h2"))
                .andExpect(jsonPath("$[1].state").value("ISOLATED"));

        // 被拒证据仍指向当时生效的 ord-h1
        mockMvc.perform(get("/api/equipment/eq-hist/rejected-readings"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value("ord-h1"));
    }

    @Test
    void notFound_branches() throws Exception {
        register("eq-nf2", 100);
        mockMvc.perform(get("/api/equipment/eq-nf2/isolations/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISOLATION_ORDER_NOT_FOUND"));
        mockMvc.perform(get("/api/isolation-orders/nope/events"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/nope/isolations"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/nope/rejected-readings"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/equipment/nope/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-nf","orderId":"ord-nf","operator":"op",
                                 "lockPointIds":["LP-A"]}
                                """))
                .andExpect(status().isNotFound());
        // 隔离令属于其他设备 → 404（不泄露）
        createIsolation("eq-nf2", "iso-own", "ord-own", "[\"LP-A\"]");
        register("eq-other", 100);
        mockMvc.perform(get("/api/equipment/eq-other/isolations/ord-own"))
                .andExpect(status().isNotFound());
    }

    @Test
    void decommission_withOpenIsolation_422() throws Exception {
        register("eq-dco", 100);
        createIsolation("eq-dco", "iso-dco", "ord-dco", "[\"LP-A\"]");
        mockMvc.perform(post("/api/equipment/eq-dco/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dec-open","expectedVersion":1,"operator":"admin"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPEN_ISOLATION_EXISTS"));
        // 解除后可停用
        isolateEffectivelyRelease("eq-dco", "ord-dco");
        mockMvc.perform(post("/api/equipment/eq-dco/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dec-ok","expectedVersion":1,"operator":"admin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECOMMISSIONED"));
        // 重复停用 → 409
        mockMvc.perform(post("/api/equipment/eq-dco/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dec-again","expectedVersion":2,"operator":"admin"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_ALREADY_DECOMMISSIONED"));
    }

    private void isolateEffectivelyRelease(String equipmentId, String orderId) throws Exception {
        confirm(equipmentId, orderId, "cf-e2-" + orderId, "ELECTRICIAN", "e1", "[\"LP-A\"]");
        confirm(equipmentId, orderId, "cf-m2-" + orderId, "MECHANIC", "m1", "[\"LP-A\"]");
        release(equipmentId, orderId, "rel-" + orderId, "[{\"pointId\":\"LP-A\",\"reason\":\"done\"}]");
    }
}
