package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 能源隔离主流程、失败分支、幂等与证据固化测试（真实 H2 内存库，MySQL 兼容模式，可控 UTC 时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableClockConfig.class)
class EnergyIsolationApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM rejected_reading");
        jdbc.update("DELETE FROM isolation_event");
        jdbc.update("DELETE FROM isolation_lock_point");
        jdbc.update("DELETE FROM energy_isolation_order");
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
        clock.reset(ControllableClockConfig.FIXED_INSTANT);
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private int postStatus(String url, String body) throws Exception {
        return postJson(url, body).getResponse().getStatus();
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

    private void createSimpleOrder(String equipmentId, String requestId, long expectedVersion)
            throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/isolation/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"operatorId":"alice",
                                 "lockPoints":[{"lockPointId":"LP1","description":"single"}]}
                                """.formatted(requestId, expectedVersion)))
                .andExpect(status().isCreated());
    }

    private void confirmRaw(String equipmentId, String lockPointId, String requestId,
                            long expectedVersion, String operator, String role) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/isolation/lock-points/"
                                + lockPointId + "/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"operatorId":"%s","lockRole":"%s"}
                                """.formatted(requestId, expectedVersion, operator, role)))
                .andExpect(status().isCreated());
    }

    private void releaseRaw(String equipmentId, String lockPointId, String requestId,
                            long expectedVersion, String operator, String reason) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/isolation/lock-points/"
                                + lockPointId + "/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"operatorId":"%s","reason":"%s"}
                                """.formatted(requestId, expectedVersion, operator, reason)))
                .andExpect(status().isCreated());
    }

    // ---------- 主流程：创建(PENDING) → 双角色确认 → 生效拦截读数 → 逐点解除 → 整令解除恢复 ----------

    @Test
    void isolationMainFlow_doubleRoleConfirm_blocksReadings_untilReleased() throws Exception {
        register("eq-iso", 1000);

        // PENDING 建立前先有一条读数
        org.junit.jupiter.api.Assertions.assertEquals(201, postStatus("/api/equipment/eq-iso/readings", """
                {"requestId":"add-before","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"operatorId":"op-r"}
                """));
        mockMvc.perform(post("/api/equipment/eq-iso/isolation/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-create","expectedVersion":2,"operatorId":"alice",
                                 "lockPoints":[
                                   {"lockPointId":"LP-ELEC","description":"主电源断路器"},
                                   {"lockPointId":"LP-GAS","description":"燃气进气阀"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.equipmentVersion").value(3))
                .andExpect(jsonPath("$.lockPointCount").value(2))
                .andExpect(jsonPath("$.confirmedLockPointCount").value(0))
                .andExpect(jsonPath("$.releasedLockPointCount").value(0))
                .andExpect(jsonPath("$.lockPoints[0].ordinal").value(0))
                .andExpect(jsonPath("$.lockPoints[0].state").value("PENDING"))
                .andExpect(jsonPath("$.lockPoints[1].ordinal").value(1));

        long orderId = jdbc.queryForObject(
                "SELECT order_id FROM energy_isolation_order WHERE equipment_id = 'eq-iso'",
                Long.class);

        // LP-ELEC：ISOLATOR 先确认 → 部分确认
        clock.advance(Duration.ofMinutes(1));
        confirmRaw("eq-iso", "LP-ELEC", "iso-c1", 3, "alice", "ISOLATOR");
        mockMvc.perform(get("/api/equipment/eq-iso/isolation/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.lockPoints[0].state").value("PARTIALLY_CONFIRMED"))
                .andExpect(jsonPath("$.lockPoints[0].isolatorConfirmedBy").value("alice"))
                .andExpect(jsonPath("$.lockPoints[0].verifierConfirmedBy").doesNotExist())
                .andExpect(jsonPath("$.equipmentVersion").value(3));

        // 同一角色再次确认（换操作人也不行）→ 422
        mockMvc.perform(post("/api/equipment/eq-iso/isolation/lock-points/LP-ELEC/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-c1-dup","expectedVersion":3,"operatorId":"carol",
                                 "lockRole":"ISOLATOR"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROLE_ALREADY_CONFIRMED"));

        // VERIFIER 另一人确认 LP-ELEC；PENDING 仍未生效，读数可写
        clock.advance(Duration.ofMinutes(1));
        confirmRaw("eq-iso", "LP-ELEC", "iso-c2", 3, "bob", "VERIFIER");
        mockMvc.perform(get("/api/equipment/eq-iso/isolation/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.lockPoints[0].state").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedLockPointCount").value(1));
        org.junit.jupiter.api.Assertions.assertEquals(201, postStatus("/api/equipment/eq-iso/readings", """
                {"requestId":"add-pending","expectedVersion":3,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":200,"operatorId":"op-r"}
                """));
        // 读数成功 → 版本 4
        mockMvc.perform(get("/api/equipment/eq-iso/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.equipmentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.currentIsolationStatus").value("PENDING"));

        // LP-GAS 双角色确认后，最后一次确认触发令生效（版本 +1 → 5）
        clock.advance(Duration.ofMinutes(1));
        confirmRaw("eq-iso", "LP-GAS", "iso-c3", 4, "alice", "ISOLATOR");
        clock.advance(Duration.ofMinutes(1));
        confirmRaw("eq-iso", "LP-GAS", "iso-c4", 4, "bob", "VERIFIER");
        mockMvc.perform(get("/api/equipment/eq-iso/isolation/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.equipmentVersion").value(5))
                .andExpect(jsonPath("$.effectiveAt").value("2026-06-01T00:04:00Z"))
                .andExpect(jsonPath("$.confirmedLockPointCount").value(2));
        mockMvc.perform(get("/api/equipment/eq-iso/status"))
                .andExpect(jsonPath("$.currentIsolationOrderId").value(orderId))
                .andExpect(jsonPath("$.currentIsolationStatus").value("EFFECTIVE"));

        // 生效后新增读数 → 409，读数不落库、版本不前进
        clock.advance(Duration.ofMinutes(1));
        MvcResult rejected = postJson("/api/equipment/eq-iso/readings", """
                {"requestId":"add-blocked","expectedVersion":5,"readingId":"r3",
                 "sampledAt":"2026-01-01T12:00:00Z","cumulativeMinutes":300,"operatorId":"op-r"}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(409, rejected.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                rejected.getResponse().getContentAsString().contains("READING_REJECTED_UNDER_ISOLATION"));
        mockMvc.perform(get("/api/equipment/eq-iso/readings"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-iso/status"))
                .andExpect(jsonPath("$.version").value(5));

        // 被拒读数证据已固化（独立事务，主事务回滚不影响）
        mockMvc.perform(get("/api/equipment/eq-iso/isolation/rejected-readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value(orderId))
                .andExpect(jsonPath("$[0].requestId").value("add-blocked"))
                .andExpect(jsonPath("$[0].operatorId").value("op-r"))
                .andExpect(jsonPath("$[0].readingId").value("r3"))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(300))
                .andExpect(jsonPath("$[0].expectedVersion").value(5))
                .andExpect(jsonPath("$[0].lockPointIds").value("[\"LP-ELEC\",\"LP-GAS\"]"))
                .andExpect(jsonPath("$[0].reasonCode").value("READING_REJECTED_UNDER_ISOLATION"));

        // 保养检查在隔离期间仍可提交
        clock.advance(Duration.ofMinutes(1));
        mockMvc.perform(post("/api/equipment/eq-iso/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-iso","expectedVersion":5,"readingId":"r2",
                                 "anchorRevisionNo":1,"operatorId":"bob"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentVersion").value(6));

        // 只解除一个点就整令解除 → 422（部分解除）
        clock.advance(Duration.ofMinutes(1));
        releaseRaw("eq-iso", "LP-ELEC", "iso-r1", 6, "dave", "电气检修完成");
        mockMvc.perform(get("/api/equipment/eq-iso/isolation/orders/" + orderId))
                .andExpect(jsonPath("$.releasedLockPointCount").value(1))
                .andExpect(jsonPath("$.lockPoints[0].state").value("RELEASED"))
                .andExpect(jsonPath("$.lockPoints[0].releasedBy").value("dave"));
        mockMvc.perform(post("/api/equipment/eq-iso/isolation/finalize-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-fin-bad","expectedVersion":6,"operatorId":"dave",
                                 "reason":"尝试提前结束"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_PARTIALLY_RELEASED"));
        // 重复解除同一点 → 422
        mockMvc.perform(post("/api/equipment/eq-iso/isolation/lock-points/LP-ELEC/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-r1-dup","expectedVersion":6,"operatorId":"dave",
                                 "reason":"再次解除"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_ALREADY_RELEASED"));
        // 解除前读数仍 409
        org.junit.jupiter.api.Assertions.assertEquals(409, postStatus("/api/equipment/eq-iso/readings", """
                {"requestId":"add-blocked-2","expectedVersion":6,"readingId":"r3",
                 "sampledAt":"2026-01-01T12:00:00Z","cumulativeMinutes":300,"operatorId":"op-r"}
                """));

        // 解除剩余点 → 整令解除成功（须填原因）
        clock.advance(Duration.ofMinutes(1));
        releaseRaw("eq-iso", "LP-GAS", "iso-r2", 6, "dave", "燃气检修完成");
        clock.advance(Duration.ofMinutes(1));
        mockMvc.perform(post("/api/equipment/eq-iso/isolation/finalize-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-fin","expectedVersion":6,"operatorId":"dave",
                                 "reason":"两处能源均已恢复"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releaseReason").value("两处能源均已恢复"))
                .andExpect(jsonPath("$.releasedBy").value("dave"))
                .andExpect(jsonPath("$.releasedAt").value("2026-06-01T00:09:00Z"))
                .andExpect(jsonPath("$.equipmentVersion").value(7));

        // 解除后读数恢复；被拒 requestId 未占键，可复用成功
        clock.advance(Duration.ofMinutes(1));
        mockMvc.perform(post("/api/equipment/eq-iso/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"add-blocked","expectedVersion":7,"readingId":"r3",
                                 "sampledAt":"2026-01-01T12:00:00Z","cumulativeMinutes":300,"operatorId":"op-r"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(1));
        mockMvc.perform(get("/api/equipment/eq-iso/readings"))
                .andExpect(jsonPath("$", hasSize(3)));

        // 事件流固化且只追加、排序稳定。实际提交顺序：CREATED + 4 CONFIRMED + EFFECTIVE
        // + REJECTED_READING(add-blocked) + RELEASE_POINT(LP-ELEC) + REJECTED_READING(add-blocked-2)
        // + RELEASE_POINT(LP-GAS) + RELEASED（保养不进隔离事件表）→ 共 11 条
        mockMvc.perform(get("/api/equipment/eq-iso/isolation/events"))
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$[1].eventType").value("CONFIRMED"))
                .andExpect(jsonPath("$[1].lockPointId").value("LP-ELEC"))
                .andExpect(jsonPath("$[1].lockRole").value("ISOLATOR"))
                .andExpect(jsonPath("$[1].operatorId").value("alice"))
                .andExpect(jsonPath("$[4].eventType").value("CONFIRMED"))
                .andExpect(jsonPath("$[4].lockPointId").value("LP-GAS"))
                .andExpect(jsonPath("$[5].eventType").value("EFFECTIVE"))
                .andExpect(jsonPath("$[6].eventType").value("REJECTED_READING"))
                .andExpect(jsonPath("$[7].eventType").value("RELEASE_POINT"))
                .andExpect(jsonPath("$[7].detail").value("电气检修完成"))
                .andExpect(jsonPath("$[8].eventType").value("REJECTED_READING"))
                .andExpect(jsonPath("$[9].eventType").value("RELEASE_POINT"))
                .andExpect(jsonPath("$[10].eventType").value("RELEASED"))
                .andExpect(jsonPath("$[10].detail").value("两处能源均已恢复"));
    }

    // ---------- 边界：隔离期间只禁新增，修订与保养仍可提交 ----------

    @Test
    void isolation_blocksNewReadingsButAllowsRevisionAndMaintenance() throws Exception {
        register("eq-revise", 10000);
        // r1=100, r3=300，中间补录空间大；先完成一次保养锚定 r1
        org.junit.jupiter.api.Assertions.assertEquals(201, postStatus("/api/equipment/eq-revise/readings", """
                {"requestId":"rv-add-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"operatorId":"op"}
                """));
        org.junit.jupiter.api.Assertions.assertEquals(201, postStatus("/api/equipment/eq-revise/readings", """
                {"requestId":"rv-add-3","expectedVersion":2,"readingId":"r3",
                 "sampledAt":"2026-01-01T12:00:00Z","cumulativeMinutes":300,"operatorId":"op"}
                """));
        // 建立隔离并生效（单点令，版本 2→3）
        createSimpleOrder("eq-revise", "rv-iso", 3);
        long orderId = jdbc.queryForObject(
                "SELECT order_id FROM energy_isolation_order WHERE equipment_id = 'eq-revise'",
                Long.class);
        confirmRaw("eq-revise", "LP1", "rv-i", 4, "alice", "ISOLATOR");
        confirmRaw("eq-revise", "LP1", "rv-v", 4, "bob", "VERIFIER");

        // 生效后新增读数被禁
        org.junit.jupiter.api.Assertions.assertEquals(409, postStatus("/api/equipment/eq-revise/readings", """
                {"requestId":"rv-blocked","expectedVersion":5,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":200,"operatorId":"op"}
                """));
        // 修订既有读数仍允许（题干只禁“新增运行读数”）
        mockMvc.perform(post("/api/equipment/eq-revise/readings/r3/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-revise","expectedVersion":5,"cumulativeMinutes":280}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.cumulativeMinutes").value(280));
        // 完成保养仍允许
        mockMvc.perform(post("/api/equipment/eq-revise/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-mnt","expectedVersion":6,"readingId":"r3",
                                 "anchorRevisionNo":2,"operatorId":"bob"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentVersion").value(7));
        // 诊断显示实际生效时长 >= 0，且要求/实际/差额一致
        mockMvc.perform(get("/api/equipment/eq-revise/isolation/diagnostics"))
                .andExpect(jsonPath("$.orderStatus").value("EFFECTIVE"))
                .andExpect(jsonPath("$.requiredLockPoints").value(1))
                .andExpect(jsonPath("$.confirmedLockPoints").value(1))
                .andExpect(jsonPath("$.unconfirmedLockPoints").value(0))
                .andExpect(jsonPath("$.effectiveDurationSeconds").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0)));

        // 历史工时与已完成保养不可改写的兜底：r3 已锚定，再修订 → 409
        mockMvc.perform(post("/api/equipment/eq-revise/readings/r3/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-revise-2","expectedVersion":7,"cumulativeMinutes":290}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));
        // 解除流程仍正常
        releaseRaw("eq-revise", "LP1", "rv-r", 7, "dave", "ok");
        mockMvc.perform(post("/api/equipment/eq-revise/isolation/finalize-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-fin","expectedVersion":7,"operatorId":"dave","reason":"ok"}
                                """))
                .andExpect(status().isCreated());
        // 解除后看历史令的实际生效时长为非负整数（可控时钟下可精确断言，这里用固定值验证语义）
        clock.advance(Duration.ofMinutes(2));
        mockMvc.perform(get("/api/equipment/eq-revise/isolation/diagnostics"))
                .andExpect(jsonPath("$.currentOrderId").doesNotExist())
                .andExpect(jsonPath("$.effectiveDurationSeconds").isNumber());
    }

    // ---------- 失败分支：422 原因码区分，不留部分结果 ----------

    @Test
    void create_lockPointDuplicate_returns422() throws Exception {
        register("eq-dup-lp", 1000);
        mockMvc.perform(post("/api/equipment/eq-dup-lp/isolation/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dup-lp","expectedVersion":1,"operatorId":"alice",
                                 "lockPoints":[
                                   {"lockPointId":"LP1","description":"a"},
                                   {"lockPointId":"LP1","description":"b"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_DUPLICATE"));
        // 失败不留令
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_lock_point", Integer.class));
        // 版本不变
        mockMvc.perform(get("/api/equipment/eq-dup-lp/status"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void create_activeOrderExists_returns422() throws Exception {
        register("eq-active", 1000);
        createSimpleOrder("eq-active", "iso-a1", 1);
        mockMvc.perform(post("/api/equipment/eq-active/isolation/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-a2","expectedVersion":2,"operatorId":"alice",
                                 "lockPoints":[{"lockPointId":"LP9","description":"x"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_ORDER_ACTIVE"));
    }

    @Test
    void deactivatedEquipment_isolationAndWritesRejected() throws Exception {
        register("eq-dead", 1000);
        // 停用设备
        mockMvc.perform(post("/api/equipment/eq-dead/deactivation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"deact-1","expectedVersion":1,"operatorId":"admin"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"))
                .andExpect(jsonPath("$.version").value(2));
        // 停用后创建隔离令 422
        mockMvc.perform(post("/api/equipment/eq-dead/isolation/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-dead","expectedVersion":2,"operatorId":"alice",
                                 "lockPoints":[{"lockPointId":"LP1","description":"x"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_DEACTIVATED"));
        // 停用后新增读数 422
        mockMvc.perform(post("/api/equipment/eq-dead/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"add-dead","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_DEACTIVATED"));
        // 停用后完成保养 422
        mockMvc.perform(post("/api/equipment/eq-dead/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-dead","expectedVersion":2,"readingId":"r1",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_DEACTIVATED"));
        // 重复停用 422
        mockMvc.perform(post("/api/equipment/eq-dead/deactivation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"deact-2","expectedVersion":2,"operatorId":"admin"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_ALREADY_DEACTIVATED"));
        // 状态保留停用语义
        mockMvc.perform(get("/api/equipment/eq-dead/status"))
                .andExpect(jsonPath("$.equipmentStatus").value("DEACTIVATED"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void deactivate_withActiveOrder_returns422() throws Exception {
        register("eq-cant-dead", 1000);
        createSimpleOrder("eq-cant-dead", "iso-cd", 1);
        mockMvc.perform(post("/api/equipment/eq-cant-dead/deactivation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"deact-x","expectedVersion":2,"operatorId":"admin"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACTIVE_ISOLATION_PREVENTS_DEACTIVATION"));
        // 已解除的历史令不阻止停用：生效→解除后可停用
        confirmRaw("eq-cant-dead", "LP1", "cd-i", 2, "alice", "ISOLATOR");
        confirmRaw("eq-cant-dead", "LP1", "cd-v", 2, "bob", "VERIFIER");
        releaseRaw("eq-cant-dead", "LP1", "cd-r", 3, "dave", "done");
        mockMvc.perform(post("/api/equipment/eq-cant-dead/isolation/finalize-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cd-fin","expectedVersion":3,"operatorId":"dave","reason":"ok"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-cant-dead/deactivation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"deact-ok","expectedVersion":4,"operatorId":"admin"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"));
        // DEACTIVATED 事件可查
        mockMvc.perform(get("/api/equipment/eq-cant-dead/isolation/events"))
                .andExpect(jsonPath("$[-1].eventType").value("DEACTIVATED"));
    }

    @Test
    void confirmAndRelease_unknownOrWrongState_returns422() throws Exception {
        register("eq-state", 1000);
        createSimpleOrder("eq-state", "iso-s", 1);
        long orderId = jdbc.queryForObject(
                "SELECT order_id FROM energy_isolation_order WHERE equipment_id = 'eq-state'",
                Long.class);
        // 未知锁定点
        mockMvc.perform(post("/api/equipment/eq-state/isolation/lock-points/NOPE/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-nope","expectedVersion":2,"operatorId":"alice",
                                 "lockRole":"ISOLATOR"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_NOT_FOUND"));
        // 未生效时解除 → 422
        mockMvc.perform(post("/api/equipment/eq-state/isolation/lock-points/LP1/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-early","expectedVersion":2,"operatorId":"dave",
                                 "reason":"提前解除"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_NOT_EFFECTIVE"));
        // 生效后再确认 → 422
        confirmRaw("eq-state", "LP1", "s-i", 2, "alice", "ISOLATOR");
        confirmRaw("eq-state", "LP1", "s-v", 2, "bob", "VERIFIER");
        mockMvc.perform(post("/api/equipment/eq-state/isolation/lock-points/LP1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-after","expectedVersion":3,"operatorId":"alice",
                                 "lockRole":"ISOLATOR"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_ALREADY_EFFECTIVE"));
        // 生效后整令解除但点未解除 → 422
        mockMvc.perform(post("/api/equipment/eq-state/isolation/finalize-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fin-partial","expectedVersion":3,"operatorId":"dave",
                                 "reason":"尝试结束"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_PARTIALLY_RELEASED"));
        // 解除并结束后再操作 → 422
        releaseRaw("eq-state", "LP1", "s-r", 3, "dave", "done");
        mockMvc.perform(post("/api/equipment/eq-state/isolation/finalize-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fin-ok","expectedVersion":3,"operatorId":"dave","reason":"done"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-state/isolation/lock-points/LP1/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rl-after","expectedVersion":4,"operatorId":"dave",
                                 "reason":"已结束"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_ISOLATION"));
        // 已结束令的明细仍可查询
        mockMvc.perform(get("/api/equipment/eq-state/isolation/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void confirm_invalidRole_badRequest() throws Exception {
        register("eq-role", 1000);
        createSimpleOrder("eq-role", "iso-role", 1);
        mockMvc.perform(post("/api/equipment/eq-role/isolation/lock-points/LP1/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-bad-role","expectedVersion":2,"operatorId":"alice",
                                 "lockRole":"ADMIN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 幂等：重放快照、异参 409、失败不占键 ----------

    @Test
    void isolationIdempotency_replayMismatchFailureNotOccupying() throws Exception {
        register("eq-idem-iso", 1000);
        String body = """
                {"requestId":"idem-iso","expectedVersion":1,"operatorId":"alice",
                 "lockPoints":[{"lockPointId":"LP1","description":"断路器"},
                               {"lockPointId":"LP2","description":"气阀"}]}
                """;
        MvcResult first = postJson("/api/equipment/eq-idem-iso/isolation/orders", body);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());
        // 同参重放：首次完整响应快照（含 orderId、时间、锁定点数组）
        MvcResult replay = postJson("/api/equipment/eq-idem-iso/isolation/orders", body);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 只创建了一条令
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id = 'eq-idem-iso'",
                Integer.class));

        // 同键异参（操作人不同）→ 409
        mockMvc.perform(post("/api/equipment/eq-idem-iso/isolation/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-iso","expectedVersion":1,"operatorId":"mallory",
                                 "lockPoints":[{"lockPointId":"LP1","description":"断路器"},
                                               {"lockPointId":"LP2","description":"气阀"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：重复锁定点 422（此时已有活动令，失败原因仍为锁定点重复，先于活动令校验）
        mockMvc.perform(post("/api/equipment/eq-idem-iso/isolation/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-iso-fail","expectedVersion":2,"operatorId":"alice",
                                 "lockPoints":[{"lockPointId":"X","description":"a"},
                                               {"lockPointId":"X","description":"b"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_DUPLICATE"));

        // 把现有令完整走完并解除
        long firstOrder = jdbc.queryForObject(
                "SELECT order_id FROM energy_isolation_order WHERE equipment_id = 'eq-idem-iso'"
                        + " ORDER BY order_id LIMIT 1", Long.class);
        confirmRaw("eq-idem-iso", "LP1", "ii-i1", 2, "alice", "ISOLATOR");
        confirmRaw("eq-idem-iso", "LP1", "ii-v1", 2, "bob", "VERIFIER");
        confirmRaw("eq-idem-iso", "LP2", "ii-i2", 2, "alice", "ISOLATOR");
        confirmRaw("eq-idem-iso", "LP2", "ii-v2", 2, "bob", "VERIFIER");
        releaseRaw("eq-idem-iso", "LP1", "ii-r1", 3, "dave", "ok");
        releaseRaw("eq-idem-iso", "LP2", "ii-r2", 3, "dave", "ok");
        mockMvc.perform(post("/api/equipment/eq-idem-iso/isolation/finalize-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ii-fin","expectedVersion":3,"operatorId":"dave","reason":"ok"}
                                """))
                .andExpect(status().isCreated());
        // 复用曾失败的 requestId 成功创建第二道令
        mockMvc.perform(post("/api/equipment/eq-idem-iso/isolation/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-iso-fail","expectedVersion":4,"operatorId":"alice",
                                 "lockPoints":[{"lockPointId":"X","description":"a"},
                                               {"lockPointId":"Y","description":"b"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 确认操作的幂等：同键同参重放返回同一快照；同键异角色 → 409
        String confirmBody = """
                {"requestId":"idem-confirm","expectedVersion":5,"operatorId":"alice",
                 "lockRole":"ISOLATOR"}
                """;
        MvcResult c1 = postJson(
                "/api/equipment/eq-idem-iso/isolation/lock-points/X/confirmations", confirmBody);
        MvcResult c2 = postJson(
                "/api/equipment/eq-idem-iso/isolation/lock-points/X/confirmations", confirmBody);
        org.junit.jupiter.api.Assertions.assertEquals(201, c1.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(c1.getResponse().getContentAsString(),
                c2.getResponse().getContentAsString());
        mockMvc.perform(post("/api/equipment/eq-idem-iso/isolation/lock-points/X/confirmations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-confirm","expectedVersion":5,"operatorId":"alice",
                                 "lockRole":"VERIFIER"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 批量隔离：整批校验、整批提交/回滚 ----------

    @Test
    void batchIsolation_successAndAtomicRollback() throws Exception {
        register("eq-b1", 1000);
        register("eq-b2", 1000);

        // 成功整批：两设备各建一令，同一事务提交
        mockMvc.perform(post("/api/isolation/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-1","operatorId":"alice","items":[
                                  {"equipmentId":"eq-b1","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"B1-LP","description":"b1"}]},
                                  {"equipmentId":"eq-b2","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"B2-LP","description":"b2"}]}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.orders[0].equipmentId").value("eq-b1"))
                .andExpect(jsonPath("$.orders[0].status").value("PENDING"))
                .andExpect(jsonPath("$.orders[1].equipmentId").value("eq-b2"));
        mockMvc.perform(get("/api/equipment/eq-b1/status"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.currentIsolationStatus").value("PENDING"));
        mockMvc.perform(get("/api/equipment/eq-b2/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 整批中第二设备锁定点重复 → 整批不生效：新令一条都没有，版本不变
        register("eq-b3", 1000);
        register("eq-b4", 1000);
        mockMvc.perform(post("/api/isolation/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-bad","operatorId":"alice","items":[
                                  {"equipmentId":"eq-b3","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"OK","description":"ok"}]},
                                  {"equipmentId":"eq-b4","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"DUP","description":"x"},
                                                 {"lockPointId":"DUP","description":"y"}]}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_DUPLICATE"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id IN ('eq-b3','eq-b4')",
                Integer.class));
        mockMvc.perform(get("/api/equipment/eq-b3/status"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/equipment/eq-b4/status"))
                .andExpect(jsonPath("$.version").value(1));

        // 批次内设备重复 → 422
        mockMvc.perform(post("/api/isolation/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-dup-eq","operatorId":"alice","items":[
                                  {"equipmentId":"eq-b3","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"P","description":"p"}]},
                                  {"equipmentId":"eq-b3","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"Q","description":"q"}]}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_DUPLICATE_EQUIPMENT"));

        // 批次中一台设备不存在 → 整批回滚，已校验通过的设备也无令
        mockMvc.perform(post("/api/isolation/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-miss","operatorId":"alice","items":[
                                  {"equipmentId":"eq-b3","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"P","description":"p"}]},
                                  {"equipmentId":"eq-ghost","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"G","description":"g"}]}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id = 'eq-b3'",
                Integer.class));

        // 版本不符同样整批回滚
        mockMvc.perform(post("/api/isolation/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-ver","operatorId":"alice","items":[
                                  {"equipmentId":"eq-b3","expectedVersion":99,
                                   "lockPoints":[{"lockPointId":"P","description":"p"}]},
                                  {"equipmentId":"eq-b4","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"Q","description":"q"}]}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id IN ('eq-b3','eq-b4')",
                Integer.class));

        // 同参重放批量：返回首次快照，不重复建行
        MvcResult replayBatch = postJson("/api/isolation/batch", """
                {"requestId":"batch-1","operatorId":"alice","items":[
                  {"equipmentId":"eq-b1","expectedVersion":1,
                   "lockPoints":[{"lockPointId":"B1-LP","description":"b1"}]},
                  {"equipmentId":"eq-b2","expectedVersion":1,
                   "lockPoints":[{"lockPointId":"B2-LP","description":"b2"}]}]}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, replayBatch.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id IN ('eq-b1','eq-b2')",
                Integer.class));

        // 批次中一台已停用 → 整批 422，另一台不留令
        mockMvc.perform(post("/api/equipment/eq-b3/deactivation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b3-dead","expectedVersion":1,"operatorId":"admin"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/isolation/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-dead","operatorId":"alice","items":[
                                  {"equipmentId":"eq-b3","expectedVersion":2,
                                   "lockPoints":[{"lockPointId":"P","description":"p"}]},
                                  {"equipmentId":"eq-b4","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"Q","description":"q"}]}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_DEACTIVATED"));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM energy_isolation_order WHERE equipment_id = 'eq-b4'",
                Integer.class));
    }

    // ---------- 诊断：左闭右开区间、实际/要求/差额/比例 ----------

    @Test
    void diagnostics_halfOpenWindowAndCounts() throws Exception {
        register("eq-diag", 1000);
        // t0 = 固定时钟；创建令
        mockMvc.perform(post("/api/equipment/eq-diag/isolation/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d-create","expectedVersion":1,"operatorId":"alice",
                                 "lockPoints":[{"lockPointId":"L1","description":"a"},
                                               {"lockPointId":"L2","description":"b"}]}
                                """))
                .andExpect(status().isCreated());
        long orderId = jdbc.queryForObject(
                "SELECT order_id FROM energy_isolation_order WHERE equipment_id = 'eq-diag'",
                Long.class);
        clock.advance(Duration.ofMinutes(1));
        confirmRaw("eq-diag", "L1", "d-i1", 2, "alice", "ISOLATOR");
        clock.advance(Duration.ofMinutes(1));
        confirmRaw("eq-diag", "L1", "d-v1", 2, "bob", "VERIFIER");

        // PENDING 期间：要求 2、确认 1、差额 1、比例 0.5；解除均 0
        mockMvc.perform(get("/api/equipment/eq-diag/isolation/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredLockPoints").value(2))
                .andExpect(jsonPath("$.confirmedLockPoints").value(1))
                .andExpect(jsonPath("$.unconfirmedLockPoints").value(1))
                .andExpect(jsonPath("$.releasedLockPoints").value(0))
                .andExpect(jsonPath("$.unreleasedLockPoints").value(2))
                .andExpect(jsonPath("$.confirmedRatio").value(0.5))
                .andExpect(jsonPath("$.releasedRatio").value(0.0))
                .andExpect(jsonPath("$.orderStatus").value("PENDING"))
                .andExpect(jsonPath("$.windowFrom").doesNotExist())
                .andExpect(jsonPath("$.windowTo").doesNotExist());

        // 左闭右开：[00:01, 00:02) 仅含 t=00:01 的一次确认，不含 t=00:00 的 CREATED 与 t=00:02
        mockMvc.perform(get("/api/equipment/eq-diag/isolation/diagnostics")
                        .param("from", "2026-06-01T00:01:00Z")
                        .param("to", "2026-06-01T00:02:00Z"))
                .andExpect(jsonPath("$.eventsInWindow").value(1))
                .andExpect(jsonPath("$.events[0].eventType").value("CONFIRMED"))
                .andExpect(jsonPath("$.rejectedReadingsInWindow").value(0))
                .andExpect(jsonPath("$.windowFrom").value("2026-06-01T00:01:00Z"))
                .andExpect(jsonPath("$.windowTo").value("2026-06-01T00:02:00Z"));

        // 边界：to 端点开区间，恰在 to 的事件不计入；from 端点闭区间，恰在 from 的事件计入
        mockMvc.perform(get("/api/equipment/eq-diag/isolation/diagnostics")
                        .param("to", "2026-06-01T00:01:00Z"))
                .andExpect(jsonPath("$.eventsInWindow").value(1));
        mockMvc.perform(get("/api/equipment/eq-diag/isolation/diagnostics")
                        .param("from", "2026-06-01T00:01:00Z"))
                .andExpect(jsonPath("$.eventsInWindow").value(2));

        // 非法区间参数 → 400
        mockMvc.perform(get("/api/equipment/eq-diag/isolation/diagnostics")
                        .param("from", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));

        // 无活动令时要求值为 0、比例为 0，历史事件仍在全量窗口内
        confirmRaw("eq-diag", "L2", "d-i2", 2, "alice", "ISOLATOR");
        confirmRaw("eq-diag", "L2", "d-v2", 2, "bob", "VERIFIER");
        releaseRaw("eq-diag", "L1", "d-r1", 3, "dave", "ok");
        releaseRaw("eq-diag", "L2", "d-r2", 3, "dave", "ok");
        mockMvc.perform(post("/api/equipment/eq-diag/isolation/finalize-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d-fin","expectedVersion":3,"operatorId":"dave","reason":"ok"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-diag/isolation/diagnostics"))
                .andExpect(jsonPath("$.currentOrderId").doesNotExist())
                .andExpect(jsonPath("$.orderStatus").doesNotExist())
                .andExpect(jsonPath("$.requiredLockPoints").value(0))
                .andExpect(jsonPath("$.confirmedRatio").value(0.0))
                .andExpect(jsonPath("$.eventsInWindow").value(9));
    }

    @Test
    void evidence_survivesReleaseAndHistoryStaysStable() throws Exception {
        register("eq-hist", 1000);
        createSimpleOrder("eq-hist", "h-create", 1);
        long orderId = jdbc.queryForObject(
                "SELECT order_id FROM energy_isolation_order WHERE equipment_id = 'eq-hist'",
                Long.class);
        confirmRaw("eq-hist", "LP1", "h-i", 2, "alice", "ISOLATOR");
        confirmRaw("eq-hist", "LP1", "h-v", 2, "bob", "VERIFIER");
        // 两次被拒读数
        org.junit.jupiter.api.Assertions.assertEquals(409, postStatus("/api/equipment/eq-hist/readings", """
                {"requestId":"rej-1","expectedVersion":3,"readingId":"x1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":10,"operatorId":"op1"}
                """));
        org.junit.jupiter.api.Assertions.assertEquals(409, postStatus("/api/equipment/eq-hist/readings", """
                {"requestId":"rej-2","expectedVersion":3,"readingId":"x2",
                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":20}
                """));
        // 无 operatorId 时证据字段为 null
        mockMvc.perform(get("/api/equipment/eq-hist/isolation/rejected-readings"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].operatorId").value("op1"))
                .andExpect(jsonPath("$[1].operatorId").doesNotExist())
                .andExpect(jsonPath("$[0].rejectedId").isNumber());

        releaseRaw("eq-hist", "LP1", "h-r", 3, "dave", "done");
        mockMvc.perform(post("/api/equipment/eq-hist/isolation/finalize-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-fin","expectedVersion":3,"operatorId":"dave","reason":"done"}
                                """))
                .andExpect(status().isCreated());

        // 解除后旧证据仍在、顺序与内容不变；令列表保留历史令
        mockMvc.perform(get("/api/equipment/eq-hist/isolation/rejected-readings"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].requestId").value("rej-1"))
                .andExpect(jsonPath("$[1].requestId").value("rej-2"));
        mockMvc.perform(get("/api/equipment/eq-hist/isolation/orders"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("RELEASED"))
                .andExpect(jsonPath("$[0].orderId").value(orderId))
                .andExpect(jsonPath("$[0].lockPoints[0].state").value("RELEASED"));
        // 工时历史字段未被隔离流程改写：读数表无被拒读数
        mockMvc.perform(get("/api/equipment/eq-hist/readings"))
                .andExpect(jsonPath("$.length()").value(0));
        // 查询不改状态：连续两次诊断结果一致（CREATED+2确认+生效+2被拒+逐点解除+整令解除=8）
        mockMvc.perform(get("/api/equipment/eq-hist/isolation/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventsInWindow").value(8));
        mockMvc.perform(get("/api/equipment/eq-hist/isolation/diagnostics"))
                .andExpect(jsonPath("$.eventsInWindow").value(8));
    }
}
