package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 设备能源隔离主流程与失败分支测试（真实 H2 内存库，MySQL 兼容模式；可控 UTC 时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClock.Config.class)
class IsolationApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM rejected_reading");
        jdbc.update("DELETE FROM isolation_confirmation");
        jdbc.update("DELETE FROM isolation_lock_point");
        jdbc.update("DELETE FROM isolation_order");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
        clock.setInstant(MutableClock.Config.INITIAL);
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
                .andExpect(jsonPath("$.state").value("ACTIVE"));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d,"operatorId":"op-reader"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().is(expectedStatus));
    }

    private long createIsolation(String equipmentId, String requestId, long expectedVersion,
                                 String operator, String pointsJson) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"operatorId":"%s","lockPoints":%s}
                                """.formatted(requestId, expectedVersion, operator, pointsJson)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.requiredRoleCount").value(2));
        Long id = jdbc.queryForObject(
                "SELECT isolation_id FROM isolation_order WHERE equipment_id = ?", Long.class, equipmentId);
        return id == null ? -1L : id;
    }

    private MockHttpServletRequestBuilder confirm(String equipmentId, long isolationId, String requestId,
                                                  long expectedVersion, String operator, String role) {
        return post("/api/equipment/" + equipmentId + "/isolations/" + isolationId + "/confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","expectedVersion":%d,"operatorId":"%s","role":"%s"}
                        """.formatted(requestId, expectedVersion, operator, role));
    }

    private MockHttpServletRequestBuilder release(String equipmentId, long isolationId, String lockPointId,
                                                  String requestId, long expectedVersion, String operator,
                                                  String reason) {
        return post("/api/equipment/" + equipmentId + "/isolations/" + isolationId
                        + "/lock-points/" + lockPointId + "/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","expectedVersion":%d,"operatorId":"%s","reason":"%s"}
                        """.formatted(requestId, expectedVersion, operator, reason));
    }

    // ---------- 主流程：创建 → 双角色确认 → 读数被拒/保养可提交 → 逐一解除 → 读数恢复 ----------

    @Test
    void fullLifecycle_readingBlockedUntilAllLockPointsReleased() throws Exception {
        register("eq-life", 1000);
        addReading("eq-life", "add-r0", 1, "r0", "2026-01-01T09:00:00Z", 100, 201);

        long isolationId = createIsolation("eq-life", "iso-1", 2, "op-chief",
                """
                [{"lockPointId":"LP-1","label":"总闸"},{"lockPointId":"LP-2","label":null}]
                """);

        // PENDING：确认数 0/2，计数完整，生效时刻为 null
        mockMvc.perform(get("/api/equipment/eq-life/isolations/" + isolationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.confirmedRoleCount").value(0))
                .andExpect(jsonPath("$.requiredRoleCount").value(2))
                .andExpect(jsonPath("$.lockPointCount").value(2))
                .andExpect(jsonPath("$.releasedLockPointCount").value(0))
                .andExpect(jsonPath("$.remainingLockPointCount").value(2))
                .andExpect(jsonPath("$.effectiveAt").value(nullValue()))
                .andExpect(jsonPath("$.lockPoints[0].lockPointId").value("LP-1"))
                .andExpect(jsonPath("$.lockPoints[0].label").value("总闸"))
                .andExpect(jsonPath("$.lockPoints[1].lockPointId").value("LP-2"))
                .andExpect(jsonPath("$.lockPoints[1].label").value(nullValue()));

        // 仅 PENDING（未生效）时读数仍可提交
        addReading("eq-life", "add-r-pending", 3, "rp", "2026-01-01T09:30:00Z", 120, 201);

        // 第一名角色确认：仍 PENDING，确认数 1/2
        clock.advance(Duration.ofSeconds(60));
        mockMvc.perform(confirm("eq-life", isolationId, "cfm-1", 4, "alice", "ELECTRICIAN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.confirmedRoleCount").value(1))
                .andExpect(jsonPath("$.confirmations[0].role").value("ELECTRICIAN"))
                .andExpect(jsonPath("$.confirmations[0].operatorId").value("alice"));

        // 同角色再次确认 → 422
        mockMvc.perform(confirm("eq-life", isolationId, "cfm-1x", 5, "alice-2", "ELECTRICIAN"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROLE_ALREADY_CONFIRMED"));

        // 第二名不同角色确认 → ACTIVE 生效，生效时刻为当前可控时钟
        clock.advance(Duration.ofSeconds(60));
        mockMvc.perform(confirm("eq-life", isolationId, "cfm-2", 5, "bob", "MECHANIC"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmedRoleCount").value(2))
                .andExpect(jsonPath("$.effectiveAt").value("2026-03-01T00:02:00Z"))
                .andExpect(jsonPath("$.confirmations[1].role").value("MECHANIC"))
                .andExpect(jsonPath("$.equipmentVersion").value(6));

        // 生效期间新增读数 → 409 且固化证据；保养检查项可继续提交
        MvcResult rejected = postJson("/api/equipment/eq-life/readings", """
                {"requestId":"add-blocked","expectedVersion":6,"readingId":"rb",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":160,"operatorId":"op-reader"}
                """);
        assertEquals(409, rejected.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                rejected.getResponse().getContentAsString().contains("EQUIPMENT_ISOLATED"));

        // 保养检查项不受隔离限制
        mockMvc.perform(post("/api/equipment/eq-life/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-iso","expectedVersion":6,"readingId":"r0",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentVersion").value(7));

        // 被拒读数未落库；诊断证据已固化（拒绝不推进版本）
        mockMvc.perform(get("/api/equipment/eq-life/readings"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-life/rejected-readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].readingId").value("rb"))
                .andExpect(jsonPath("$[0].operatorId").value("op-reader"))
                .andExpect(jsonPath("$[0].requestId").value("add-blocked"))
                .andExpect(jsonPath("$[0].isolationId").value(isolationId))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(160))
                .andExpect(jsonPath("$[0].lockPointsSnapshot").value("[\"LP-1\",\"LP-2\"]"));

        // 部分解除：先解 LP-2，隔离仍 ACTIVE，计数 1 已解除 / 1 剩余，读数仍 409
        clock.advance(Duration.ofSeconds(120));
        mockMvc.perform(release("eq-life", isolationId, "LP-2", "rel-2", 7, "bob", "机械锁已挂牌复核"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.releasedLockPointCount").value(1))
                .andExpect(jsonPath("$.remainingLockPointCount").value(1))
                .andExpect(jsonPath("$.effectiveDurationSeconds").value(120));
        addReading("eq-life", "add-blocked-2", 8, "rb2", "2026-01-01T10:30:00Z", 180, 409);

        // 重复解除同一锁定点 → 422
        mockMvc.perform(release("eq-life", isolationId, "LP-2", "rel-2x", 8, "bob", "再解一次"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_ALREADY_RELEASED"));
        // 不存在的锁定点 → 404
        mockMvc.perform(release("eq-life", isolationId, "LP-X", "rel-x", 8, "bob", "原因"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_NOT_FOUND"));

        // 解除原因必填
        mockMvc.perform(post("/api/equipment/eq-life/isolations/" + isolationId
                        + "/lock-points/LP-1/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rel-blank","expectedVersion":8,"operatorId":"bob","reason":""}
                                """))
                .andExpect(status().isBadRequest());

        // 最后一个锁定点解除 → RELEASED，左闭右开生效时长 120 秒（00:02 → 00:04）
        mockMvc.perform(release("eq-life", isolationId, "LP-1", "rel-1", 8, "alice", "电气锁验电完成"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.releasedLockPointCount").value(2))
                .andExpect(jsonPath("$.remainingLockPointCount").value(0))
                .andExpect(jsonPath("$.effectiveDurationSeconds").value(120))
                .andExpect(jsonPath("$.releasedBy").value("alice"))
                .andExpect(jsonPath("$.lockPoints[0].releaseReason").value("电气锁验电完成"))
                .andExpect(jsonPath("$.lockPoints[0].releasedBy").value("alice"))
                .andExpect(jsonPath("$.lockPoints[1].releaseReason").value("机械锁已挂牌复核"));

        // 解除后读数恢复提交；解除后再确认/解除均 422
        addReading("eq-life", "add-after", 9, "ra", "2026-01-01T11:00:00Z", 220, 201);
        mockMvc.perform(confirm("eq-life", isolationId, "cfm-late", 10, "carol", "CHEMIST"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_ALREADY_RELEASED"));
        mockMvc.perform(release("eq-life", isolationId, "LP-1", "rel-late", 10, "alice", "再解"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_ALREADY_RELEASED"));

        // 历史证据不随解除/读数重提而改写：仍是两条被拒记录；隔离历史按创建时刻升序
        mockMvc.perform(get("/api/equipment/eq-life/rejected-readings"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].readingId").value("rb"))
                .andExpect(jsonPath("$[1].readingId").value("rb2"));
        mockMvc.perform(get("/api/equipment/eq-life/isolations"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].isolationId").value(isolationId));
    }

    // ---------- 创建失败分支 ----------

    @Test
    void create_lockPointDuplicateAndOpenIsolationAndDecommissioned_422() throws Exception {
        register("eq-bad", 1000);

        // 锁定点重复 → 422，不创建隔离令
        mockMvc.perform(post("/api/equipment/eq-bad/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-dup","expectedVersion":1,"operatorId":"chief",
                                 "lockPoints":[{"lockPointId":"LP-1"},{"lockPointId":"LP-1"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_DUPLICATE"));
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_order WHERE equipment_id = 'eq-bad'", Integer.class);
        assertEquals(0, orderCount, "422 失败不得留下隔离令");

        // 空锁定点集合 → 400
        mockMvc.perform(post("/api/equipment/eq-bad/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-empty","expectedVersion":1,"operatorId":"chief",
                                 "lockPoints":[]}
                                """))
                .andExpect(status().isBadRequest());

        createIsolation("eq-bad", "iso-open", 1, "chief",
                """
                [{"lockPointId":"LP-1"}]
                """);
        // 已有未完成隔离令再创建 → 422
        mockMvc.perform(post("/api/equipment/eq-bad/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-again","expectedVersion":2,"operatorId":"chief",
                                 "lockPoints":[{"lockPointId":"LP-9"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_ALREADY_OPEN"));

        // 停用设备上创建隔离令 → 422；停用设备读数写操作同样 422
        register("eq-off", 1000);
        mockMvc.perform(post("/api/equipment/eq-off/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"off-1","expectedVersion":1,"operatorId":"boss"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("DECOMMISSIONED"));
        mockMvc.perform(post("/api/equipment/eq-off/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-off","expectedVersion":2,"operatorId":"chief",
                                 "lockPoints":[{"lockPointId":"LP-1"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_DECOMMISSIONED"));
        addReading("eq-off", "off-r", 2, "r1", "2026-01-01T10:00:00Z", 10, 422);
    }

    @Test
    void decommission_blockedWhileIsolationOpen() throws Exception {
        register("eq-dc", 1000);
        long isolationId = createIsolation("eq-dc", "iso-dc", 1, "chief",
                """
                [{"lockPointId":"LP-1"}]
                """);
        // PENDING 时不可停用
        mockMvc.perform(post("/api/equipment/eq-dc/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-1","expectedVersion":2,"operatorId":"boss"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_STILL_OPEN"));
        // 生效后仍不可停用
        mockMvc.perform(confirm("eq-dc", isolationId, "dc-c1", 2, "a", "R1"))
                .andExpect(status().isCreated());
        mockMvc.perform(confirm("eq-dc", isolationId, "dc-c2", 3, "b", "R2"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-dc/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-2","expectedVersion":4,"operatorId":"boss"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_STILL_OPEN"));
        // 全部解除后可停用
        mockMvc.perform(release("eq-dc", isolationId, "LP-1", "dc-r", 4, "a", "完工"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-dc/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-3","expectedVersion":5,"operatorId":"boss"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("DECOMMISSIONED"));
        // 重复停用 → 422
        mockMvc.perform(post("/api/equipment/eq-dc/decommission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dc-4","expectedVersion":6,"operatorId":"boss"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_DECOMMISSIONED"));
    }

    // ---------- 批量隔离：完整校验，任一失败整批不生效 ----------

    @Test
    void batch_successAndIdempotency() throws Exception {
        register("eq-b1", 1000);
        register("eq-b2", 1000);
        String body = """
                {"requestId":"batch-1","operatorId":"chief","items":[
                  {"equipmentId":"eq-b1","expectedVersion":1,
                   "lockPoints":[{"lockPointId":"A-1"}]},
                  {"equipmentId":"eq-b2","expectedVersion":1,
                   "lockPoints":[{"lockPointId":"B-1","label":"阀门"},{"lockPointId":"B-2"}]}
                ]}
                """;
        MvcResult first = postJson("/api/equipment/isolations/batch", body);
        assertEquals(201, first.getResponse().getStatus());

        // 两台设备均产生 PENDING 隔离令，版本各加一
        mockMvc.perform(get("/api/equipment/eq-b1/status"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/equipment/eq-b2/status"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/equipment/eq-b2/isolations"))
                .andExpect(jsonPath("$[0].lockPointCount").value(2))
                .andExpect(jsonPath("$[0].lockPoints[0].lockPointId").value("B-1"));

        // 同参重放：首次完整响应快照
        MvcResult replay = postJson("/api/equipment/isolations/batch", body);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-b1/isolations")).andExpect(jsonPath("$", hasSize(1)));

        // 异参 → 409
        String mismatched = body.replace("\"A-1\"", "\"A-2\"");
        mockMvc.perform(post("/api/equipment/isolations/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(mismatched))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void batch_anyFailureLeavesNoPartialResult() throws Exception {
        register("eq-f1", 1000);
        register("eq-f2", 1000);
        // 让 eq-f2 版本前进到 2
        addReading("eq-f2", "f2-r", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);

        // 第二台设备版本不匹配 → 整批 409，两台都不得产生隔离令、版本不变
        mockMvc.perform(post("/api/equipment/isolations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-fail-version","operatorId":"chief","items":[
                                  {"equipmentId":"eq-f1","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"A-1"}]},
                                  {"equipmentId":"eq-f2","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"B-1"}]}
                                ]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertNoIsolationAndVersion("eq-f1", 1);
        assertNoIsolationAndVersion("eq-f2", 2);

        // 第二台设备锁定点重复 → 整批 422
        mockMvc.perform(post("/api/equipment/isolations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-fail-dup","operatorId":"chief","items":[
                                  {"equipmentId":"eq-f1","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"A-1"}]},
                                  {"equipmentId":"eq-f2","expectedVersion":2,
                                   "lockPoints":[{"lockPointId":"B-1"},{"lockPointId":"B-1"}]}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_DUPLICATE"));
        assertNoIsolationAndVersion("eq-f1", 1);
        assertNoIsolationAndVersion("eq-f2", 2);

        // 设备不存在 → 整批 404
        mockMvc.perform(post("/api/equipment/isolations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-fail-404","operatorId":"chief","items":[
                                  {"equipmentId":"eq-f1","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"A-1"}]},
                                  {"equipmentId":"eq-missing","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"C-1"}]}
                                ]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        assertNoIsolationAndVersion("eq-f1", 1);

        // 同一设备在批次内出现两次 → 422
        mockMvc.perform(post("/api/equipment/isolations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-fail-twice","operatorId":"chief","items":[
                                  {"equipmentId":"eq-f1","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"A-1"}]},
                                  {"equipmentId":"eq-f1","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"A-2"}]}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_DUPLICATE_EQUIPMENT"));
        assertNoIsolationAndVersion("eq-f1", 1);

        // 失败不占键：版本冲突用过的 requestId 修正版本后可成功，且整批生效
        mockMvc.perform(post("/api/equipment/isolations/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"batch-fail-version","operatorId":"chief","items":[
                                  {"equipmentId":"eq-f1","expectedVersion":1,
                                   "lockPoints":[{"lockPointId":"A-1"}]},
                                  {"equipmentId":"eq-f2","expectedVersion":2,
                                   "lockPoints":[{"lockPointId":"B-1"}]}
                                ]}
                                """))
                .andExpect(status().isCreated());
        assertIsolationCount("eq-f1", 1);
        assertIsolationCount("eq-f2", 1);
    }

    private void assertNoIsolationAndVersion(String equipmentId, long version) throws Exception {
        assertIsolationCount(equipmentId, 0);
        mockMvc.perform(get("/api/equipment/" + equipmentId + "/status"))
                .andExpect(jsonPath("$.version").value(version));
    }

    private void assertIsolationCount(String equipmentId, int count) {
        Integer actual = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_order WHERE equipment_id = ?", Integer.class, equipmentId);
        assertEquals(count, actual, "隔离令数量不符：" + equipmentId);
    }

    // ---------- 幂等：重放快照、异参 409、失败不占键 ----------

    @Test
    void isolation_idempotencyReplayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-idem", 1000);

        // 失败（422 锁定点重复）不占键：同一 requestId 修正参数后成功
        mockMvc.perform(post("/api/equipment/eq-idem/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-idem-1","expectedVersion":1,"operatorId":"chief",
                                 "lockPoints":[{"lockPointId":"LP-1"},{"lockPointId":"LP-1"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOCK_POINT_DUPLICATE"));
        long isolationId = createIsolation("eq-idem", "iso-idem-1", 1, "chief",
                """
                [{"lockPointId":"LP-1"},{"lockPointId":"LP-2"}]
                """);

        // 同参重放创建：集合顺序不影响指纹，响应快照完全一致，不重复创建
        MvcResult firstView = postJson("/api/equipment/eq-idem/isolations", """
                {"requestId":"iso-idem-1","expectedVersion":1,"operatorId":"chief",
                 "lockPoints":[{"lockPointId":"LP-1"},{"lockPointId":"LP-2"}]}
                """);
        MvcResult replayView = postJson("/api/equipment/eq-idem/isolations", """
                {"requestId":"iso-idem-1","expectedVersion":1,"operatorId":"chief",
                 "lockPoints":[{"lockPointId":"LP-2"},{"lockPointId":"LP-1"}]}
                """);
        assertEquals(201, firstView.getResponse().getStatus());
        assertEquals(firstView.getResponse().getContentAsString(), replayView.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-idem/isolations")).andExpect(jsonPath("$", hasSize(1)));

        // 异参（少一个锁定点）→ 409
        mockMvc.perform(post("/api/equipment/eq-idem/isolations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"iso-idem-1","expectedVersion":1,"operatorId":"chief",
                                 "lockPoints":[{"lockPointId":"LP-1"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 确认同参重放：仍只有一条确认记录，响应为首次快照（PENDING/1 人确认）
        MvcResult cfmFirst = mockMvc.perform(
                confirm("eq-idem", isolationId, "cfm-idem", 2, "a", "R1")).andReturn();
        assertEquals(201, cfmFirst.getResponse().getStatus());
        MvcResult cfmReplay = mockMvc.perform(
                confirm("eq-idem", isolationId, "cfm-idem", 2, "a", "R1")).andReturn();
        assertEquals(201, cfmReplay.getResponse().getStatus());
        assertEquals(cfmFirst.getResponse().getContentAsString(), cfmReplay.getResponse().getContentAsString());
        Integer confirmations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM isolation_confirmation WHERE isolation_id = ?", Integer.class, isolationId);
        assertEquals(1, confirmations);

        // 确认异参（换角色）→ 409
        mockMvc.perform(confirm("eq-idem", isolationId, "cfm-idem", 3, "a", "OTHER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 第二名角色让隔离生效
        mockMvc.perform(confirm("eq-idem", isolationId, "cfm-idem-2", 3, "b", "R2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ACTIVE"));

        // 解除同参重放：返回首次解除时快照（ACTIVE，已解除 1 个），不产生重复效果
        MvcResult relFirst = mockMvc.perform(
                release("eq-idem", isolationId, "LP-1", "rel-idem", 4, "a", "原因一")).andReturn();
        assertEquals(201, relFirst.getResponse().getStatus());
        MvcResult relReplay = mockMvc.perform(
                release("eq-idem", isolationId, "LP-1", "rel-idem", 4, "a", "原因一")).andReturn();
        assertEquals(201, relReplay.getResponse().getStatus());
        assertEquals(relFirst.getResponse().getContentAsString(), relReplay.getResponse().getContentAsString());
        // 重放不改状态：仍剩 1 个锁定点
        mockMvc.perform(get("/api/equipment/eq-idem/isolations/" + isolationId))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.remainingLockPointCount").value(1));
        // 同键换原因 → 409
        mockMvc.perform(release("eq-idem", isolationId, "LP-1", "rel-idem", 4, "a", "不同原因"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 重复解除后，requestId 可用于解除另一个锁定点
        mockMvc.perform(release("eq-idem", isolationId, "LP-1", "rel-reuse", 5, "a", "再解"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(release("eq-idem", isolationId, "LP-2", "rel-reuse", 5, "b", "原因二"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RELEASED"));
    }

    // ---------- 查询不改状态、跨设备隔离令 404、状态机分支 ----------

    @Test
    void queries_doNotModifyState_andIsolationScopedToEquipment() throws Exception {
        register("eq-q1", 1000);
        register("eq-q2", 1000);
        long isolationId = createIsolation("eq-q1", "iso-q1", 1, "chief",
                """
                [{"lockPointId":"LP-1"}]
                """);

        // 跨设备访问隔离令 → 404
        mockMvc.perform(get("/api/equipment/eq-q2/isolations/" + isolationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISOLATION_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/eq-q2/isolations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-missing/isolations"))
                .andExpect(status().isNotFound());

        // 连续查询不改变版本
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/equipment/eq-q1/isolations/" + isolationId))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/equipment/eq-q1/rejected-readings"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/equipment/eq-q1/status"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.equipmentState").value("ACTIVE"));
    }

    @Test
    void pendingRelease_422_andConfirmAfterActive_422() throws Exception {
        register("eq-state", 1000);
        long isolationId = createIsolation("eq-state", "iso-state", 1, "chief",
                """
                [{"lockPointId":"LP-1"},{"lockPointId":"LP-2"}]
                """);

        // PENDING 时解除 → 422
        mockMvc.perform(release("eq-state", isolationId, "LP-1", "rel-early", 2, "a", "提前解除"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_NOT_ACTIVE"));

        mockMvc.perform(confirm("eq-state", isolationId, "s-c1", 2, "a", "R1"))
                .andExpect(status().isCreated());
        mockMvc.perform(confirm("eq-state", isolationId, "s-c2", 3, "b", "R2"))
                .andExpect(status().isCreated());

        // ACTIVE 后第三个角色确认 → 422
        mockMvc.perform(confirm("eq-state", isolationId, "s-c3", 4, "c", "R3"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ISOLATION_ALREADY_ACTIVE"));
    }
}
