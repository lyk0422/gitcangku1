package com.example.starter.maintenance;

import static org.hamcrest.Matchers.containsString;
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
 * 保养延期 API 测试：申请/双人审批/拒绝、单次与累计上限、超期封锁、周期重置与幂等（真实 H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeferralApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM deferral");
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

    private MvcResult apply(String equipmentId, String requestId, String deferKey, long minutes,
                            String applicant, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/equipment/" + equipmentId + "/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","deferKey":"%s","minutes":%d,
                                 "reason":"备件待到货","applicant":"%s"}
                                """.formatted(requestId, deferKey, minutes, applicant)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private MvcResult approve(String equipmentId, String deferKey, String requestId,
                              String approver, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/equipment/" + equipmentId + "/deferrals/"
                        + deferKey + "/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","approver":"%s"}
                                """.formatted(requestId, approver)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private MvcResult reject(String equipmentId, String deferKey, String requestId,
                             String approver, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/equipment/" + equipmentId + "/deferrals/"
                        + deferKey + "/rejection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","approver":"%s","reason":"备件已到货，可按期保养"}
                                """.formatted(requestId, approver)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private void completeMaintenance(String equipmentId, String requestId, long expectedVersion,
                                     String readingId, int anchorRevisionNo,
                                     int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s","anchorRevisionNo":%d}
                                """.formatted(requestId, expectedVersion, readingId, anchorRevisionNo)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------- 主流程：申请 → 待审批封锁 → 批准 → 新阈值 → OVERDUE 封锁 → 保养后重置 ----------

    @Test
    void mainFlow_deferralExtendsThresholdAndOverdueLocksReadings() throws Exception {
        register("eq-d1", 100);
        addReading("eq-d1", "d1-r1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);

        // DUE：当前阈值 100，无延期，未封锁
        mockMvc.perform(get("/api/equipment/eq-d1/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(100))
                .andExpect(jsonPath("$.currentThresholdMinutes").value(100))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(0))
                .andExpect(jsonPath("$.pendingDeferralKey").value(nullValue()))
                .andExpect(jsonPath("$.readingBlocked").value(false));

        // 申请延期 20 分钟（申请人 zhang）
        apply("eq-d1", "d1-apply", "dk1", 20, "zhang", 201);
        mockMvc.perform(get("/api/equipment/eq-d1/deferrals"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].deferKey").value("dk1"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].applicant").value("zhang"))
                .andExpect(jsonPath("$[0].appliedRunMinutes").value(110))
                .andExpect(jsonPath("$[0].requestedMinutes").value(20));

        // 待审批不改变 DUE 状态，但封锁新增读数
        mockMvc.perform(get("/api/equipment/eq-d1/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.pendingDeferralKey").value("dk1"))
                .andExpect(jsonPath("$.readingBlocked").value(true));
        addReading("eq-d1", "d1-r-blocked", 2, "rx", "2026-01-01T11:00:00Z", 115, 409);

        // 双人审批：wang 批准，阈值 100 → 120
        approve("eq-d1", "dk1", "d1-approve", "wang", 200);
        mockMvc.perform(get("/api/equipment/eq-d1/deferrals"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].approver").value("wang"))
                .andExpect(jsonPath("$[0].originalThresholdMinutes").value(100))
                .andExpect(jsonPath("$[0].newThresholdMinutes").value(120));

        // 批准后：阈值 120，解除封锁，状态仍 DUE
        mockMvc.perform(get("/api/equipment/eq-d1/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.currentThresholdMinutes").value(120))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(20))
                .andExpect(jsonPath("$.pendingDeferralKey").value(nullValue()))
                .andExpect(jsonPath("$.readingBlocked").value(false));

        // 新阈值前允许读数；跨过新阈值的读数本身允许
        addReading("eq-d1", "d1-r2", 2, "r2", "2026-01-01T11:00:00Z", 119, 201);
        addReading("eq-d1", "d1-r3", 3, "r3", "2026-01-01T12:00:00Z", 125, 201);

        // 达到新阈值仍未保养 → OVERDUE，后续读数 409
        mockMvc.perform(get("/api/equipment/eq-d1/status"))
                .andExpect(jsonPath("$.status").value("OVERDUE"))
                .andExpect(jsonPath("$.runMinutes").value(125))
                .andExpect(jsonPath("$.readingBlocked").value(true));
        mockMvc.perform(post("/api/equipment/eq-d1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d1-r4","expectedVersion":4,"readingId":"r4",
                                 "sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":130}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_BLOCKED_OVERDUE"));

        // 完成保养：周期重置，阈值回到原始周期，延期额度归零
        completeMaintenance("eq-d1", "d1-mnt", 4, "r3", 1, 201);
        mockMvc.perform(get("/api/equipment/eq-d1/status"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.currentThresholdMinutes").value(100))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(0))
                .andExpect(jsonPath("$.readingBlocked").value(false));

        // 延期记录不可变：固化申请时工时、原/新阈值、双方操作人与原因
        mockMvc.perform(get("/api/equipment/eq-d1/deferrals"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].appliedRunMinutes").value(110))
                .andExpect(jsonPath("$[0].originalThresholdMinutes").value(100))
                .andExpect(jsonPath("$[0].newThresholdMinutes").value(120))
                .andExpect(jsonPath("$[0].applicant").value("zhang"))
                .andExpect(jsonPath("$[0].approver").value("wang"))
                .andExpect(jsonPath("$[0].reason").value("备件待到货"));
    }

    // ---------- 申请前置条件 ----------

    @Test
    void apply_notDue_rejected422() throws Exception {
        register("eq-d2", 100);
        addReading("eq-d2", "d2-r1", 1, "r1", "2026-01-01T10:00:00Z", 50, 201);
        mockMvc.perform(post("/api/equipment/eq-d2/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d2-apply","deferKey":"dk1","minutes":10,
                                 "reason":"test","applicant":"zhang"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEFERRAL_NOT_DUE"));
        mockMvc.perform(get("/api/equipment/eq-d2/deferrals"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void apply_invalidParams_badRequest() throws Exception {
        register("eq-d3", 100);
        // 分钟数下限 1
        mockMvc.perform(post("/api/equipment/eq-d3/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d3-a1","deferKey":"dk1","minutes":0,
                                 "reason":"test","applicant":"zhang"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 分钟数上限 10080
        mockMvc.perform(post("/api/equipment/eq-d3/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d3-a2","deferKey":"dk1","minutes":10081,
                                 "reason":"test","applicant":"zhang"}
                                """))
                .andExpect(status().isBadRequest());
        // 原因必填
        mockMvc.perform(post("/api/equipment/eq-d3/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d3-a3","deferKey":"dk1","minutes":10,"applicant":"zhang"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ---------- 单次 25% 与累计 50% 上限 ----------

    @Test
    void apply_singleAndCumulativeLimits() throws Exception {
        register("eq-d4", 100);
        addReading("eq-d4", "d4-r1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);

        // 单次超过 25%（25 分钟）→ 422，报文含允许上限；失败不占 requestId 键
        mockMvc.perform(post("/api/equipment/eq-d4/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d4-a1","deferKey":"dk1","minutes":26,
                                 "reason":"test","applicant":"zhang"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEFERRAL_SINGLE_LIMIT"))
                .andExpect(jsonPath("$.message", containsString("25")));
        apply("eq-d4", "d4-a1", "dk1", 25, "zhang", 201);
        approve("eq-d4", "dk1", "d4-ap1", "wang", 200);

        // 第二次 25：累计 50，恰好达到 50% 上限，允许
        apply("eq-d4", "d4-a2", "dk2", 25, "zhang", 201);
        approve("eq-d4", "dk2", "d4-ap2", "wang", 200);
        mockMvc.perform(get("/api/equipment/eq-d4/status"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(50))
                .andExpect(jsonPath("$.currentThresholdMinutes").value(150));

        // 再申请 1 分钟：累计将超 50% → 422，报文含已累计与允许上限
        mockMvc.perform(post("/api/equipment/eq-d4/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d4-a3","deferKey":"dk3","minutes":1,
                                 "reason":"test","applicant":"zhang"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEFERRAL_CUMULATIVE_LIMIT"))
                .andExpect(jsonPath("$.message", containsString("已累计 50")))
                .andExpect(jsonPath("$.message", containsString("允许上限 50")));
    }

    @Test
    void apply_pendingExistsAndKeyReuse_conflict() throws Exception {
        register("eq-d5", 100);
        addReading("eq-d5", "d5-r1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);

        apply("eq-d5", "d5-a1", "dk1", 20, "zhang", 201);
        // 同时只允许一条待审批延期
        mockMvc.perform(post("/api/equipment/eq-d5/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d5-a2","deferKey":"dk2","minutes":10,
                                 "reason":"test","applicant":"zhang"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFERRAL_PENDING_EXISTS"));

        // 拒绝后 deferKey 不可复用（设备内唯一，含历史）
        reject("eq-d5", "dk1", "d5-rj", "wang", 200);
        mockMvc.perform(post("/api/equipment/eq-d5/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d5-a3","deferKey":"dk1","minutes":10,
                                 "reason":"test","applicant":"zhang"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFERRAL_KEY_EXISTS"));
    }

    // ---------- 双人审批与拒绝 ----------

    @Test
    void approve_selfApprovalRejected_thenOtherApproverSucceeds() throws Exception {
        register("eq-d6", 100);
        addReading("eq-d6", "d6-r1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);
        apply("eq-d6", "d6-a1", "dk1", 20, "zhang", 201);

        // 申请人不能批准自己的申请
        mockMvc.perform(post("/api/equipment/eq-d6/deferrals/dk1/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d6-ap1","approver":"zhang"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEFERRAL_SELF_APPROVE"));

        // 仍未决，另一维护角色可批准
        mockMvc.perform(get("/api/equipment/eq-d6/deferrals"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        approve("eq-d6", "dk1", "d6-ap2", "wang", 200);

        // 已批准的记录不可再批准/拒绝
        approve("eq-d6", "dk1", "d6-ap3", "li", 409);
        reject("eq-d6", "dk1", "d6-rj1", "li", 409);
    }

    @Test
    void reject_recordsReasonAndReapplyStillUnderCumulativeLimit() throws Exception {
        register("eq-d7", 100);
        addReading("eq-d7", "d7-r1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);

        apply("eq-d7", "d7-a1", "dk1", 25, "zhang", 201);
        approve("eq-d7", "dk1", "d7-ap1", "wang", 200);

        // 拒绝须记录理由；拒绝不计入累计延期
        apply("eq-d7", "d7-a2", "dk2", 25, "zhang", 201);
        reject("eq-d7", "dk2", "d7-rj1", "wang", 200);
        mockMvc.perform(get("/api/equipment/eq-d7/deferrals"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].status").value("REJECTED"))
                .andExpect(jsonPath("$[1].approver").value("wang"))
                .andExpect(jsonPath("$[1].rejectReason").value("备件已到货，可按期保养"));

        // 重新申请（新 deferKey）：已批准累计仅 25，再批 25 恰好到 50% 上限
        apply("eq-d7", "d7-a3", "dk3", 25, "zhang", 201);
        approve("eq-d7", "dk3", "d7-ap3", "wang", 200);
        mockMvc.perform(get("/api/equipment/eq-d7/status"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(50));

        // 拒绝记录保留在历史中
        mockMvc.perform(get("/api/equipment/eq-d7/deferrals"))
                .andExpect(jsonPath("$", hasSize(3)));
    }

    // ---------- 保养完成使待审批延期失效 ----------

    @Test
    void maintenanceCompletion_expiresPendingDeferral() throws Exception {
        register("eq-d8", 100);
        addReading("eq-d8", "d8-r1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);
        apply("eq-d8", "d8-a1", "dk1", 20, "zhang", 201);

        // 保养完成先提交：待审批延期失效，后续批准 409
        completeMaintenance("eq-d8", "d8-mnt", 2, "r1", 1, 201);
        mockMvc.perform(post("/api/equipment/eq-d8/deferrals/dk1/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d8-ap1","approver":"wang"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFERRAL_NOT_PENDING"));
        mockMvc.perform(get("/api/equipment/eq-d8/deferrals"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));

        // 新周期重新运行到 DUE 后可再次申请，额度从零计算
        addReading("eq-d8", "d8-r2", 3, "r2", "2026-01-01T11:00:00Z", 220, 201);
        apply("eq-d8", "d8-a2", "dk2", 25, "zhang", 201);
        approve("eq-d8", "dk2", "d8-ap2", "wang", 200);
        mockMvc.perform(get("/api/equipment/eq-d8/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(25))
                .andExpect(jsonPath("$.currentThresholdMinutes").value(125));
    }

    // ---------- 幂等 ----------

    @Test
    void idempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-d9", 100);
        addReading("eq-d9", "d9-r1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);

        MvcResult first = apply("eq-d9", "d9-apply", "dk1", 20, "zhang", 201);
        // 同键同参重放：返回首次结果，不产生新记录
        MvcResult replay = apply("eq-d9", "d9-apply", "dk1", 20, "zhang", 201);
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-d9/deferrals"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-d9/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d9-apply","deferKey":"dk1","minutes":21,
                                 "reason":"备件待到货","applicant":"zhang"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 批准同样幂等：重放返回首次批准结果
        MvcResult approveFirst = approve("eq-d9", "dk1", "d9-approve", "wang", 200);
        MvcResult approveReplay = approve("eq-d9", "dk1", "d9-approve", "wang", 200);
        org.junit.jupiter.api.Assertions.assertEquals(
                approveFirst.getResponse().getContentAsString(),
                approveReplay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-d9/deferrals"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));

        // 失败不占键：自审批 422 后，同 requestId 换审批人可成功
        mockMvc.perform(post("/api/equipment/eq-d9/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d9-a2","deferKey":"dk2","minutes":30,
                                 "reason":"test","applicant":"zhang"}
                                """))
                .andExpect(status().isUnprocessableEntity());
        apply("eq-d9", "d9-a2", "dk2", 20, "zhang", 201);
    }

    // ---------- 资源不存在 ----------

    @Test
    void notFound_branches() throws Exception {
        mockMvc.perform(post("/api/equipment/nope/deferrals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nf-d1","deferKey":"dk1","minutes":10,
                                 "reason":"test","applicant":"zhang"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope/deferrals"))
                .andExpect(status().isNotFound());

        register("eq-d10", 100);
        mockMvc.perform(post("/api/equipment/eq-d10/deferrals/nope/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nf-d2","approver":"wang"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFERRAL_NOT_FOUND"));
        mockMvc.perform(post("/api/equipment/eq-d10/deferrals/nope/rejection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nf-d3","approver":"wang","reason":"x"}
                                """))
                .andExpect(status().isNotFound());
    }
}
