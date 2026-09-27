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
 * 设备能源隔离 API 主流程与失败分支测试（真实 H2 内存库，MySQL 兼容模式）。
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
        jdbc.update("DELETE FROM isolated_reading_rejection");
        jdbc.update("DELETE FROM isolation_lock_release");
        jdbc.update("DELETE FROM isolation_confirmation");
        jdbc.update("DELETE FROM isolation_lock_point");
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
                .andExpect(status().isCreated());
    }

    private MvcResult createIsolation(String equipmentId, String requestId, String orderId,
                                      long expectedVersion, String lockPointsJson) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/isolation-orders", """
                {"requestId":"%s","orderId":"%s","createdBy":"op-create","expectedVersion":%d,
                 "lockPoints":%s}
                """.formatted(requestId, orderId, expectedVersion, lockPointsJson));
    }

    private MvcResult confirm(String orderId, String requestId, String lockPointId,
                              String operatorId, String roleId) throws Exception {
        return postJson("/api/isolation-orders/" + orderId + "/confirmations", """
                {"requestId":"%s","lockPointId":"%s","operatorId":"%s","roleId":"%s"}
                """.formatted(requestId, lockPointId, operatorId, roleId));
    }

    private MvcResult release(String orderId, String requestId, String reason,
                              String releasesJson) throws Exception {
        return postJson("/api/isolation-orders/" + orderId + "/release", """
                {"requestId":"%s","operatorId":"op-release","reason":"%s","releases":%s}
                """.formatted(requestId, reason, releasesJson));
    }

    private MvcResult addReading(String equipmentId, String requestId, long expectedVersion,
                                 String readingId, String sampledAt, long cumulativeMinutes,
                                 String operatorId) throws Exception {
        String operator = operatorId == null ? "null" : "\"" + operatorId + "\"";
        return postJson("/api/equipment/" + equipmentId + "/readings", """
                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                 "sampledAt":"%s","cumulativeMinutes":%d,"operatorId":%s}
                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                cumulativeMinutes, operator));
    }

    /** 用两名不同角色确认全部锁定点，使隔离令生效。 */
    private void activate(String orderId, String... lockPointIds) throws Exception {
        for (String lockPointId : lockPointIds) {
            confirm(orderId, "cf-" + lockPointId + "-a", lockPointId, "op-a", "ELECTRICIAN")
                    .getResponse();
            confirm(orderId, "cf-" + lockPointId + "-b", lockPointId, "op-b", "SUPERVISOR")
                    .getResponse();
        }
    }

    // ---------- 主流程：创建 → 双角色确认生效 → 读数被拒 → 保养可提交 → 逐一解除 → 读数恢复 ----------

    @Test
    void mainFlow_isolationLifecycle() throws Exception {
        register("eq-iso", 100);
        addReading("eq-iso", "rd-1", 1, "r1", "2026-01-01T10:00:00Z", 50, "op-meter")
                .getResponse();

        // 创建隔离令（PENDING）：两个锁定点
        MvcResult created = createIsolation("eq-iso", "iso-1", "order-1", 2,
                """
                [{"lockPointId":"lp-main","name":"主电源"},{"lockPointId":"lp-air","name":"气源"}]
                """);
        assertEquals(201, created.getResponse().getStatus());
        mockMvc.perform(get("/api/isolation-orders/order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdBy").value("op-create"))
                .andExpect(jsonPath("$.lockPointCount").value(2))
                .andExpect(jsonPath("$.confirmedLockPointCount").value(0))
                .andExpect(jsonPath("$.remainingLockPointCount").value(2))
                .andExpect(jsonPath("$.activatedAt").doesNotExist())
                .andExpect(jsonPath("$.lockPoints", hasSize(2)))
                .andExpect(jsonPath("$.lockPoints[0].lockPointId").value("lp-main"))
                .andExpect(jsonPath("$.lockPoints[0].requiredRoleCount").value(2))
                .andExpect(jsonPath("$.lockPoints[0].confirmedRoleCount").value(0))
                .andExpect(jsonPath("$.lockPoints[0].missingRoleCount").value(2))
                .andExpect(jsonPath("$.lockPoints[1].lockPointId").value("lp-air"));

        // PENDING 期间读数仍可提交（隔离尚未生效）
        addReading("eq-iso", "rd-2", 3, "r2", "2026-01-01T11:00:00Z", 80, "op-meter")
                .getResponse();

        // 第一名角色确认 lp-main：尚未生效
        MvcResult c1 = confirm("order-1", "cf-1", "lp-main", "op-a", "ELECTRICIAN");
        assertEquals(201, c1.getResponse().getStatus());
        mockMvc.perform(get("/api/isolation-orders/order-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.confirmedLockPointCount").value(0))
                .andExpect(jsonPath("$.lockPoints[0].confirmedRoleCount").value(1))
                .andExpect(jsonPath("$.lockPoints[0].missingRoleCount").value(1));

        // 第二名不同角色确认 lp-main → 该点集齐；再确认 lp-air 两名角色 → 隔离生效
        confirm("order-1", "cf-2", "lp-main", "op-b", "SUPERVISOR");
        confirm("order-1", "cf-3", "lp-air", "op-a", "ELECTRICIAN");
        MvcResult c4 = confirm("order-1", "cf-4", "lp-air", "op-c", "SUPERVISOR");
        assertEquals(201, c4.getResponse().getStatus());
        mockMvc.perform(get("/api/isolation-orders/order-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmedLockPointCount").value(2))
                .andExpect(jsonPath("$.remainingLockPointCount").value(0))
                .andExpect(jsonPath("$.activatedAt").exists())
                .andExpect(jsonPath("$.effectiveFrom").exists())
                .andExpect(jsonPath("$.effectiveTo").doesNotExist());

        // 隔离生效：新增运行读数 409，拒绝证据固化（锁定点快照 + 操作人）
        MvcResult rejected = addReading("eq-iso", "rd-3", 5, "r3",
                "2026-01-01T12:00:00Z", 120, "op-meter");
        assertEquals(409, rejected.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-iso/isolated-reading-rejections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value("order-1"))
                .andExpect(jsonPath("$[0].readingId").value("r3"))
                .andExpect(jsonPath("$[0].operatorId").value("op-meter"))
                .andExpect(jsonPath("$[0].requestId").value("rd-3"))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(120))
                .andExpect(jsonPath("$[0].lockPointCount").value(2))
                .andExpect(jsonPath("$[0].lockPointSnapshot[0]").value("lp-air"))
                .andExpect(jsonPath("$[0].lockPointSnapshot[1]").value("lp-main"));
        // 被拒读数未落库
        mockMvc.perform(get("/api/equipment/eq-iso/readings"))
                .andExpect(jsonPath("$", hasSize(2)));

        // 保养检查项可继续提交（以既有读数为锚点）
        mockMvc.perform(post("/api/equipment/eq-iso/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-iso","expectedVersion":5,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 解除：逐一解除全部锁定点并写原因
        MvcResult released = release("order-1", "rel-1", "检修完成，恢复送电",
                """
                [{"lockPointId":"lp-main","operatorId":"op-a"},
                 {"lockPointId":"lp-air","operatorId":"op-b"}]
                """);
        assertEquals(201, released.getResponse().getStatus());
        mockMvc.perform(get("/api/isolation-orders/order-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedBy").value("op-release"))
                .andExpect(jsonPath("$.releaseReason").value("检修完成，恢复送电"))
                .andExpect(jsonPath("$.releasedLockPointCount").value(2))
                .andExpect(jsonPath("$.unreleasedLockPointCount").value(0))
                .andExpect(jsonPath("$.effectiveTo").exists());

        // 解除后读数恢复（版本：1 登记 →2 读数 →3 建隔离令 →4 PENDING 读数 →5 生效
        // →6 保养 →7 解除；故期望版本 7）
        MvcResult after = addReading("eq-iso", "rd-4", 7, "r3",
                "2026-01-01T12:00:00Z", 120, "op-meter");
        assertEquals(201, after.getResponse().getStatus());

        // 隔离历史：创建/生效/解除三事件，操作人与原因固化
        mockMvc.perform(get("/api/equipment/eq-iso/isolation-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].eventType").value("CREATED"))
                .andExpect(jsonPath("$[0].operatorId").value("op-create"))
                .andExpect(jsonPath("$[0].requestId").value("iso-1"))
                .andExpect(jsonPath("$[1].eventType").value("ACTIVATED"))
                .andExpect(jsonPath("$[1].operatorId").value("op-c"))
                .andExpect(jsonPath("$[1].requestId").value("cf-4"))
                .andExpect(jsonPath("$[2].eventType").value("RELEASED"))
                .andExpect(jsonPath("$[2].operatorId").value("op-release"))
                .andExpect(jsonPath("$[2].reason").value("检修完成，恢复送电"));

        // 设备隔离令列表
        mockMvc.perform(get("/api/equipment/eq-iso/isolation-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value("order-1"))
                .andExpect(jsonPath("$[0].status").value("RELEASED"));
    }

    // ---------- 创建校验 ----------

    @Test
    void create_duplicateLockPoint_returns422() throws Exception {
        register("eq-dlp", 100);
        MvcResult result = createIsolation("eq-dlp", "iso-dlp", "order-dlp", 1,
                """
                [{"lockPointId":"lp-1"},{"lockPointId":"lp-1"}]
                """);
        assertEquals(422, result.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                result.getResponse().getContentAsString().contains("DUPLICATE_LOCK_POINT"));
        // 失败不占键：修正参数后同 requestId 可成功
        MvcResult fixed = createIsolation("eq-dlp", "iso-dlp", "order-dlp", 1,
                """
                [{"lockPointId":"lp-1"},{"lockPointId":"lp-2"}]
                """);
        assertEquals(201, fixed.getResponse().getStatus());
    }

    @Test
    void create_equipmentAlreadyIsolated_returns422() throws Exception {
        register("eq-open", 100);
        createIsolation("eq-open", "iso-a", "order-a", 1,
                """
                [{"lockPointId":"lp-1"}]
                """);
        // 存在未解除隔离令（含 PENDING）→ 422
        MvcResult second = createIsolation("eq-open", "iso-b", "order-b", 2,
                """
                [{"lockPointId":"lp-9"}]
                """);
        assertEquals(422, second.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                second.getResponse().getContentAsString().contains("EQUIPMENT_ALREADY_ISOLATED"));
    }

    @Test
    void create_versionConflictAndOrderIdReuse() throws Exception {
        register("eq-vc", 100);
        MvcResult wrong = createIsolation("eq-vc", "iso-vc", "order-vc", 99,
                """
                [{"lockPointId":"lp-1"}]
                """);
        assertEquals(409, wrong.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                wrong.getResponse().getContentAsString().contains("VERSION_CONFLICT"));

        createIsolation("eq-vc", "iso-vc2", "order-vc", 1,
                """
                [{"lockPointId":"lp-1"}]
                """);
        register("eq-vc2", 100);
        // 隔离令标识全局唯一
        MvcResult reused = createIsolation("eq-vc2", "iso-vc3", "order-vc", 1,
                """
                [{"lockPointId":"lp-1"}]
                """);
        assertEquals(422, reused.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                reused.getResponse().getContentAsString().contains("ISOLATION_ORDER_EXISTS"));
    }

    // ---------- 确认校验 ----------

    @Test
    void confirm_sameRoleDoubleConfirm_returns422() throws Exception {
        register("eq-role", 100);
        createIsolation("eq-role", "iso-r", "order-r", 1,
                """
                [{"lockPointId":"lp-1"}]
                """);
        confirm("order-r", "cf-r1", "lp-1", "op-a", "ELECTRICIAN");
        // 同一角色（即使不同操作人）双确认 → 422
        MvcResult dup = confirm("order-r", "cf-r2", "lp-1", "op-b", "ELECTRICIAN");
        assertEquals(422, dup.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                dup.getResponse().getContentAsString().contains("ROLE_ALREADY_CONFIRMED"));
        // 不同角色可确认，隔离生效
        MvcResult ok = confirm("order-r", "cf-r3", "lp-1", "op-b", "SUPERVISOR");
        assertEquals(201, ok.getResponse().getStatus());
        mockMvc.perform(get("/api/isolation-orders/order-r"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void confirm_unknownLockPointAndReleasedOrder() throws Exception {
        register("eq-cnf", 100);
        createIsolation("eq-cnf", "iso-c", "order-c", 1,
                """
                [{"lockPointId":"lp-1"}]
                """);
        MvcResult notFound = confirm("order-c", "cf-x", "lp-nope", "op-a", "ELECTRICIAN");
        assertEquals(404, notFound.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                notFound.getResponse().getContentAsString().contains("LOCK_POINT_NOT_FOUND"));

        activate("order-c", "lp-1");
        release("order-c", "rel-c", "完成",
                """
                [{"lockPointId":"lp-1","operatorId":"op-a"}]
                """);
        // 已生效/解除的隔离令不可再确认 → 422
        MvcResult afterRelease = confirm("order-c", "cf-y", "lp-1", "op-c", "ELECTRICIAN");
        assertEquals(422, afterRelease.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                afterRelease.getResponse().getContentAsString().contains("ISOLATION_NOT_PENDING"));
    }

    // ---------- 解除校验 ----------

    @Test
    void release_partialOrMismatched_returns422() throws Exception {
        register("eq-rel", 100);
        createIsolation("eq-rel", "iso-p", "order-p", 1,
                """
                [{"lockPointId":"lp-1"},{"lockPointId":"lp-2"}]
                """);
        // 未生效（PENDING）不可解除 → 422
        MvcResult notActive = release("order-p", "rel-p0", "提前解除",
                """
                [{"lockPointId":"lp-1","operatorId":"op-a"},
                 {"lockPointId":"lp-2","operatorId":"op-b"}]
                """);
        assertEquals(422, notActive.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                notActive.getResponse().getContentAsString().contains("ISOLATION_NOT_ACTIVE"));

        activate("order-p", "lp-1", "lp-2");

        // 部分解除（缺失 lp-2）→ 422
        MvcResult partial = release("order-p", "rel-p1", "只解除一个",
                """
                [{"lockPointId":"lp-1","operatorId":"op-a"}]
                """);
        assertEquals(422, partial.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                partial.getResponse().getContentAsString().contains("PARTIAL_LOCK_RELEASE"));

        // 多余锁定点 → 422
        MvcResult extra = release("order-p", "rel-p2", "多一个",
                """
                [{"lockPointId":"lp-1","operatorId":"op-a"},
                 {"lockPointId":"lp-2","operatorId":"op-b"},
                 {"lockPointId":"lp-3","operatorId":"op-c"}]
                """);
        assertEquals(422, extra.getResponse().getStatus());

        // 重复锁定点 → 422
        MvcResult duplicated = release("order-p", "rel-p3", "重复",
                """
                [{"lockPointId":"lp-1","operatorId":"op-a"},
                 {"lockPointId":"lp-1","operatorId":"op-b"}]
                """);
        assertEquals(422, duplicated.getResponse().getStatus());

        // 失败均不占键、不改变状态：完整解除仍可用任一 requestId 成功
        MvcResult ok = release("order-p", "rel-p1", "全部解除",
                """
                [{"lockPointId":"lp-1","operatorId":"op-a"},
                 {"lockPointId":"lp-2","operatorId":"op-b"}]
                """);
        assertEquals(201, ok.getResponse().getStatus());
        mockMvc.perform(get("/api/isolation-orders/order-p"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releaseReason").value("全部解除"));
    }

    // ---------- 批量隔离 ----------

    @Test
    void batch_allOrNothing() throws Exception {
        register("eq-b1", 100);
        register("eq-b2", 100);
        register("eq-b3", 100);

        // 任一设备校验失败（eq-b2 锁定点重复）→ 整批不生效
        MvcResult failed = postJson("/api/isolation-orders/batch", """
                {"requestId":"batch-1","createdBy":"op-batch",
                 "orders":[
                   {"equipmentId":"eq-b1","orderId":"bo-1","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]},
                   {"equipmentId":"eq-b2","orderId":"bo-2","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"},{"lockPointId":"lp-1"}]},
                   {"equipmentId":"eq-b3","orderId":"bo-3","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]}
                 ]}
                """);
        assertEquals(422, failed.getResponse().getStatus());
        // 查询不到半成品：三台设备均无隔离令，版本均未推进
        for (String eq : new String[]{"eq-b1", "eq-b2", "eq-b3"}) {
            mockMvc.perform(get("/api/equipment/" + eq + "/isolation-orders"))
                    .andExpect(jsonPath("$", hasSize(0)));
            mockMvc.perform(get("/api/equipment/" + eq + "/status"))
                    .andExpect(jsonPath("$.version").value(1));
        }
        Integer orderCount = jdbc.queryForObject("SELECT COUNT(*) FROM isolation_order",
                Integer.class);
        assertEquals(0, orderCount, "批量失败不得留下任何隔离令");

        // 失败不占键：修正后同 requestId 整批成功
        MvcResult ok = postJson("/api/isolation-orders/batch", """
                {"requestId":"batch-1","createdBy":"op-batch",
                 "orders":[
                   {"equipmentId":"eq-b1","orderId":"bo-1","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]},
                   {"equipmentId":"eq-b2","orderId":"bo-2","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"},{"lockPointId":"lp-2"}]},
                   {"equipmentId":"eq-b3","orderId":"bo-3","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]}
                 ]}
                """);
        assertEquals(201, ok.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-b1/isolation-orders"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value("bo-1"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        mockMvc.perform(get("/api/equipment/eq-b2/isolation-orders"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-b3/isolation-orders"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 同键同参重放：返回首次完整响应快照，不重复创建
        MvcResult replay = postJson("/api/isolation-orders/batch", """
                {"requestId":"batch-1","createdBy":"op-batch",
                 "orders":[
                   {"equipmentId":"eq-b1","orderId":"bo-1","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]},
                   {"equipmentId":"eq-b2","orderId":"bo-2","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"},{"lockPointId":"lp-2"}]},
                   {"equipmentId":"eq-b3","orderId":"bo-3","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]}
                 ]}
                """);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(ok.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-b1/isolation-orders"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 同键异参 → 409
        MvcResult mismatch = postJson("/api/isolation-orders/batch", """
                {"requestId":"batch-1","createdBy":"op-batch",
                 "orders":[
                   {"equipmentId":"eq-b1","orderId":"bo-1","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-9"}]}
                 ]}
                """);
        assertEquals(409, mismatch.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                mismatch.getResponse().getContentAsString().contains("REQUEST_ID_CONFLICT"));
    }

    @Test
    void batch_itemVersionConflict_rollsBackAll() throws Exception {
        register("eq-bv1", 100);
        register("eq-bv2", 100);
        // 第二项版本不符 → 409 且整批回滚
        MvcResult result = postJson("/api/isolation-orders/batch", """
                {"requestId":"batch-v","createdBy":"op-batch",
                 "orders":[
                   {"equipmentId":"eq-bv1","orderId":"bov-1","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]},
                   {"equipmentId":"eq-bv2","orderId":"bov-2","expectedVersion":7,
                    "lockPoints":[{"lockPointId":"lp-1"}]}
                 ]}
                """);
        assertEquals(409, result.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-bv1/isolation-orders"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-bv2/isolation-orders"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void batch_duplicateItemsInRequest_returns422() throws Exception {
        register("eq-d1", 100);
        // 批内设备重复 → 422
        MvcResult dupEquipment = postJson("/api/isolation-orders/batch", """
                {"requestId":"batch-d","createdBy":"op-batch",
                 "orders":[
                   {"equipmentId":"eq-d1","orderId":"bd-1","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]},
                   {"equipmentId":"eq-d1","orderId":"bd-2","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-2"}]}
                 ]}
                """);
        assertEquals(422, dupEquipment.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                dupEquipment.getResponse().getContentAsString().contains("BATCH_ITEM_DUPLICATE"));
        mockMvc.perform(get("/api/equipment/eq-d1/isolation-orders"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 批内隔离令标识重复（设备不同）→ 422
        register("eq-d2", 100);
        MvcResult dupOrder = postJson("/api/isolation-orders/batch", """
                {"requestId":"batch-d2","createdBy":"op-batch",
                 "orders":[
                   {"equipmentId":"eq-d1","orderId":"bd-x","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]},
                   {"equipmentId":"eq-d2","orderId":"bd-x","expectedVersion":1,
                    "lockPoints":[{"lockPointId":"lp-1"}]}
                 ]}
                """);
        assertEquals(422, dupOrder.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                dupOrder.getResponse().getContentAsString().contains("BATCH_ITEM_DUPLICATE"));
    }

    // ---------- 超额确认 ----------

    @Test
    void confirm_thirdRoleAfterTwo_returns422() throws Exception {
        register("eq-3r", 100);
        createIsolation("eq-3r", "iso-3r", "order-3r", 1,
                """
                [{"lockPointId":"lp-1"}]
                """);
        confirm("order-3r", "cf-3r1", "lp-1", "op-a", "ELECTRICIAN");
        confirm("order-3r", "cf-3r2", "lp-1", "op-b", "SUPERVISOR");
        // 已集齐两名不同角色（隔离已生效），第三名角色再确认 → 422
        MvcResult third = confirm("order-3r", "cf-3r3", "lp-1", "op-c", "MECHANIC");
        assertEquals(422, third.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                third.getResponse().getContentAsString().contains("ISOLATION_NOT_PENDING"));
    }

    // ---------- 读数幂等指纹包含操作者 ----------

    @Test
    void readingFingerprint_includesOperator() throws Exception {
        register("eq-ofp", 100);
        MvcResult first = addReading("eq-ofp", "ofp-1", 1, "r1",
                "2026-01-01T10:00:00Z", 10, "op-one");
        assertEquals(201, first.getResponse().getStatus());
        // 同 requestId 但操作者不同（其余字段相同）→ 409（指纹含操作者）
        MvcResult otherOperator = addReading("eq-ofp", "ofp-1", 1, "r1",
                "2026-01-01T10:00:00Z", 10, "op-two");
        assertEquals(409, otherOperator.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                otherOperator.getResponse().getContentAsString().contains("REQUEST_ID_CONFLICT"));
    }

    // ---------- 幂等（单设备隔离令） ----------

    @Test
    void idempotency_createConfirmRelease() throws Exception {
        register("eq-idm", 100);
        MvcResult first = createIsolation("eq-idm", "iso-i", "order-i", 1,
                """
                [{"lockPointId":"lp-1"}]
                """);
        assertEquals(201, first.getResponse().getStatus());

        // 同键同参重放：返回首次完整响应快照
        MvcResult replay = createIsolation("eq-idm", "iso-i", "order-i", 1,
                """
                [{"lockPointId":"lp-1"}]
                """);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-idm/isolation-orders"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 同键异参 → 409
        MvcResult mismatch = createIsolation("eq-idm", "iso-i", "order-i", 1,
                """
                [{"lockPointId":"lp-2"}]
                """);
        assertEquals(409, mismatch.getResponse().getStatus());

        // 确认重放
        MvcResult cf1 = confirm("order-i", "cf-i1", "lp-1", "op-a", "ELECTRICIAN");
        assertEquals(201, cf1.getResponse().getStatus());
        MvcResult cf1Replay = confirm("order-i", "cf-i1", "lp-1", "op-a", "ELECTRICIAN");
        assertEquals(201, cf1Replay.getResponse().getStatus());
        assertEquals(cf1.getResponse().getContentAsString(),
                cf1Replay.getResponse().getContentAsString());
        // 确认记录不重复
        mockMvc.perform(get("/api/isolation-orders/order-i"))
                .andExpect(jsonPath("$.lockPoints[0].confirmations", hasSize(1)));

        // 生效后解除，解除重放返回首次快照
        confirm("order-i", "cf-i2", "lp-1", "op-b", "SUPERVISOR");
        MvcResult rel = release("order-i", "rel-i", "完成",
                """
                [{"lockPointId":"lp-1","operatorId":"op-a"}]
                """);
        assertEquals(201, rel.getResponse().getStatus());
        MvcResult relReplay = release("order-i", "rel-i", "完成",
                """
                [{"lockPointId":"lp-1","operatorId":"op-a"}]
                """);
        assertEquals(201, relReplay.getResponse().getStatus());
        assertEquals(rel.getResponse().getContentAsString(),
                relReplay.getResponse().getContentAsString());
        // 解除记录只有一条
        Integer releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_lock_release WHERE order_id = 'order-i'",
                Integer.class);
        assertEquals(1, releaseCount);
    }

    // ---------- 被拒读数证据细节 ----------

    @Test
    void rejectionEvidence_multipleAttemptsAndNullOperator() throws Exception {
        register("eq-rej", 100);
        createIsolation("eq-rej", "iso-e", "order-e", 1,
                """
                [{"lockPointId":"lp-b"},{"lockPointId":"lp-a"}]
                """);
        activate("order-e", "lp-b", "lp-a");

        // 两次被拒（第二次不提供操作人 → null 语义稳定），每次失败均固化证据
        MvcResult r1 = addReading("eq-rej", "rej-1", 2, "r9",
                "2026-01-01T10:00:00Z", 10, "op-x");
        assertEquals(409, r1.getResponse().getStatus());
        MvcResult r2 = addReading("eq-rej", "rej-2", 2, "r9",
                "2026-01-01T10:00:00Z", 10, null);
        assertEquals(409, r2.getResponse().getStatus());

        mockMvc.perform(get("/api/equipment/eq-rej/isolated-reading-rejections"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].requestId").value("rej-1"))
                .andExpect(jsonPath("$[0].operatorId").value("op-x"))
                .andExpect(jsonPath("$[0].lockPointSnapshot[0]").value("lp-a"))
                .andExpect(jsonPath("$[0].lockPointSnapshot[1]").value("lp-b"))
                .andExpect(jsonPath("$[1].requestId").value("rej-2"))
                .andExpect(jsonPath("$[1].operatorId").doesNotExist());

        // 失败不占幂等键：解除后同 requestId 可成功（版本 1→2 建令→3 生效→4 解除）
        release("order-e", "rel-e", "完成",
                """
                [{"lockPointId":"lp-a","operatorId":"op-a"},
                 {"lockPointId":"lp-b","operatorId":"op-b"}]
                """);
        MvcResult ok = addReading("eq-rej", "rej-1", 4, "r9",
                "2026-01-01T10:00:00Z", 10, "op-x");
        assertEquals(201, ok.getResponse().getStatus());
    }

    // ---------- 资源不存在 ----------

    @Test
    void notFound_branches() throws Exception {
        mockMvc.perform(get("/api/isolation-orders/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISOLATION_ORDER_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope/isolation-orders"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/nope/isolation-history"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/nope/isolated-reading-rejections"))
                .andExpect(status().isNotFound());
        MvcResult createMissing = createIsolation("nope", "iso-n", "order-n", 1,
                """
                [{"lockPointId":"lp-1"}]
                """);
        assertEquals(404, createMissing.getResponse().getStatus());
        MvcResult confirmMissing = confirm("nope", "cf-n", "lp-1", "op-a", "ELECTRICIAN");
        assertEquals(404, confirmMissing.getResponse().getStatus());
        MvcResult releaseMissing = release("nope", "rel-n", "x",
                """
                [{"lockPointId":"lp-1","operatorId":"op-a"}]
                """);
        assertEquals(404, releaseMissing.getResponse().getStatus());
    }
}
