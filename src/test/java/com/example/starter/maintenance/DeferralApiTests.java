package com.example.starter.maintenance;

import static org.hamcrest.Matchers.containsString;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 保养延期审批与超期运行封锁测试（真实 H2 内存库，MySQL 兼容模式）。
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

    private ResultActions postAction(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
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

    private ResultActions applyDeferral(String equipmentId, String requestId, String deferKey,
                                        long deferMinutes, String applicant) throws Exception {
        return postAction("/api/equipment/" + equipmentId + "/deferrals", """
                {"requestId":"%s","deferKey":"%s","deferMinutes":%d,
                 "reason":"等待备件到货","applicant":"%s"}
                """.formatted(requestId, deferKey, deferMinutes, applicant));
    }

    private ResultActions approve(String equipmentId, String deferKey, String requestId,
                                  String approver) throws Exception {
        return postAction("/api/equipment/" + equipmentId + "/deferrals/" + deferKey + "/approve",
                """
                {"requestId":"%s","approver":"%s"}
                """.formatted(requestId, approver));
    }

    // ---------- 主流程：DUE → 申请 → 待审批封锁读数 → 双人批准 → 阈值后移 → 超期封锁 → 保养复位 ----------

    @Test
    void mainFlow_deferralApprovalAndOverdueLockout() throws Exception {
        register("eq-def", 100);
        addReading("eq-def", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);

        // DUE：生效阈值即保养周期，无延期
        mockMvc.perform(get("/api/equipment/eq-def/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(0))
                .andExpect(jsonPath("$.effectiveThresholdMinutes").value(100))
                .andExpect(jsonPath("$.pendingDeferralKey").doesNotExist())
                .andExpect(jsonPath("$.overdueLocked").value(false));

        // 提交延期申请：固化申请时工时快照
        applyDeferral("eq-def", "d-apply-1", "dk1", 20, "alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deferKey").value("dk1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.deferMinutes").value(20))
                .andExpect(jsonPath("$.applicant").value("alice"))
                .andExpect(jsonPath("$.appliedCumulativeMinutes").value(110))
                .andExpect(jsonPath("$.cycleNo").value(0));

        // 待审批不改变 DUE 状态，但不允许新增运行读数
        mockMvc.perform(get("/api/equipment/eq-def/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.pendingDeferralKey").value("dk1"));
        addReading("eq-def", "r-blocked", 2, "r2", "2026-01-01T11:00:00Z", 115, 409);

        // 双人审批：批准人不得与申请人相同
        approve("eq-def", "dk1", "d-appr-same", "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("APPROVER_SAME_AS_APPLICANT"));

        // 不同维护角色批准：阈值 100 → 120，快照固化
        approve("eq-def", "dk1", "d-appr-1", "bob")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approver").value("bob"))
                .andExpect(jsonPath("$.originalThresholdMinutes").value(100))
                .andExpect(jsonPath("$.newThresholdMinutes").value(120));

        mockMvc.perform(get("/api/equipment/eq-def/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(20))
                .andExpect(jsonPath("$.effectiveThresholdMinutes").value(120))
                .andExpect(jsonPath("$.pendingDeferralKey").doesNotExist())
                .andExpect(jsonPath("$.overdueLocked").value(false));

        // 批准后在新阈值前允许读数；达到新阈值的读数本身仍允许
        addReading("eq-def", "r-2", 2, "r2", "2026-01-01T11:00:00Z", 119, 201);
        addReading("eq-def", "r-3", 3, "r3", "2026-01-01T12:00:00Z", 120, 201);

        // 达到新阈值仍未保养 → OVERDUE，后续读数 409
        mockMvc.perform(get("/api/equipment/eq-def/status"))
                .andExpect(jsonPath("$.status").value("OVERDUE"))
                .andExpect(jsonPath("$.overdueLocked").value(true));
        mockMvc.perform(post("/api/equipment/eq-def/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r-4","expectedVersion":4,"readingId":"r4",
                                 "sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":125}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_OVERDUE"));

        // 完成保养：延期额度归零，下一周期按原始阈值计算
        mockMvc.perform(post("/api/equipment/eq-def/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-1","expectedVersion":4,"readingId":"r3","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-def/status"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(0))
                .andExpect(jsonPath("$.effectiveThresholdMinutes").value(100))
                .andExpect(jsonPath("$.overdueLocked").value(false));

        // 延期历史：不可变记录完整保留双方操作人与快照
        mockMvc.perform(get("/api/equipment/eq-def/deferrals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].deferKey").value("dk1"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].reason").value("等待备件到货"))
                .andExpect(jsonPath("$[0].applicant").value("alice"))
                .andExpect(jsonPath("$[0].approver").value("bob"))
                .andExpect(jsonPath("$[0].appliedCumulativeMinutes").value(110))
                .andExpect(jsonPath("$[0].originalThresholdMinutes").value(100))
                .andExpect(jsonPath("$[0].newThresholdMinutes").value(120));
    }

    // ---------- 申请前置条件 ----------

    @Test
    void apply_notDue_conflict() throws Exception {
        register("eq-ok", 100);
        addReading("eq-ok", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 50, 201);
        applyDeferral("eq-ok", "d-1", "dk1", 10, "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_DUE"));
    }

    @Test
    void apply_overdueEquipment_conflict() throws Exception {
        register("eq-od", 100);
        addReading("eq-od", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);
        applyDeferral("eq-od", "d-1", "dk1", 10, "alice").andExpect(status().isCreated());
        approve("eq-od", "dk1", "d-2", "bob").andExpect(status().isOk());
        // 110 >= 110（100+10）→ 已超期封锁，不可再申请延期
        applyDeferral("eq-od", "d-3", "dk2", 10, "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_OVERDUE"));
    }

    @Test
    void apply_paramValidation_badRequest() throws Exception {
        register("eq-val", 100);
        addReading("eq-val", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        // 延期分钟数下限 1
        applyDeferral("eq-val", "d-v1", "dk1", 0, "alice")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 延期分钟数上限 10080
        applyDeferral("eq-val", "d-v2", "dk1", 10081, "alice")
                .andExpect(status().isBadRequest());
        // 申请人必填
        postAction("/api/equipment/eq-val/deferrals", """
                {"requestId":"d-v3","deferKey":"dk1","deferMinutes":10,"reason":"x"}
                """).andExpect(status().isBadRequest());
    }

    // ---------- 延期额度上限 ----------

    @Test
    void apply_singleLimitExceeded_422() throws Exception {
        register("eq-single", 100);
        addReading("eq-single", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        // 单次超过周期 25%（25 分钟）→ 422，报文含允许上限
        applyDeferral("eq-single", "d-1", "dk1", 26, "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEFERRAL_SINGLE_LIMIT"))
                .andExpect(jsonPath("$.message", containsString("25")));
        // 边界值 25 允许
        applyDeferral("eq-single", "d-2", "dk1", 25, "alice")
                .andExpect(status().isCreated());
    }

    @Test
    void apply_cumulativeLimitExceeded_422() throws Exception {
        register("eq-cum", 100);
        addReading("eq-cum", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        // 两次各 25 分钟批准，累计达 50% 上限
        applyDeferral("eq-cum", "d-1", "dk1", 25, "alice").andExpect(status().isCreated());
        approve("eq-cum", "dk1", "d-2", "bob").andExpect(status().isOk());
        applyDeferral("eq-cum", "d-3", "dk2", 25, "alice").andExpect(status().isCreated());
        approve("eq-cum", "dk2", "d-4", "bob").andExpect(status().isOk());
        mockMvc.perform(get("/api/equipment/eq-cum/status"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(50))
                .andExpect(jsonPath("$.effectiveThresholdMinutes").value(150));
        // 再申请 1 分钟即超累计上限 → 422，报文含已累计与允许上限
        applyDeferral("eq-cum", "d-5", "dk3", 1, "alice")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEFERRAL_CUMULATIVE_LIMIT"))
                .andExpect(jsonPath("$.message", containsString("已累计 50")))
                .andExpect(jsonPath("$.message", containsString("上限 50")));
    }

    // ---------- 待审批唯一性与 deferKey 唯一性 ----------

    @Test
    void apply_pendingExistsAndKeyReuse_conflict() throws Exception {
        register("eq-pend", 100);
        addReading("eq-pend", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        applyDeferral("eq-pend", "d-1", "dk1", 10, "alice").andExpect(status().isCreated());
        // 同一设备同时只允许一条待审批延期
        applyDeferral("eq-pend", "d-2", "dk2", 10, "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFERRAL_PENDING_EXISTS"));
        // 批准后 deferKey 不可复用（历史记录不可变）
        approve("eq-pend", "dk1", "d-3", "bob").andExpect(status().isOk());
        applyDeferral("eq-pend", "d-4", "dk1", 10, "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFERRAL_KEY_EXISTS"));
    }

    // ---------- 拒绝与重新申请 ----------

    @Test
    void reject_reasonRecordedAndReapply() throws Exception {
        register("eq-rej", 100);
        addReading("eq-rej", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        applyDeferral("eq-rej", "d-1", "dk1", 20, "alice").andExpect(status().isCreated());

        // 拒绝必须记录理由
        postAction("/api/equipment/eq-rej/deferrals/dk1/reject", """
                {"requestId":"d-2","approver":"bob"}
                """).andExpect(status().isBadRequest());

        postAction("/api/equipment/eq-rej/deferrals/dk1/reject", """
                {"requestId":"d-2","approver":"bob","reason":"备件已到货，可直接保养"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approver").value("bob"))
                .andExpect(jsonPath("$.rejectReason").value("备件已到货，可直接保养"));

        // 已拒绝不可再审批
        approve("eq-rej", "dk1", "d-3", "carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFERRAL_NOT_PENDING"));

        // 申请人可换新 deferKey 重新申请；被拒绝的延期不计入累计额度
        applyDeferral("eq-rej", "d-4", "dk2", 20, "alice").andExpect(status().isCreated());
        approve("eq-rej", "dk2", "d-5", "bob").andExpect(status().isOk());
        mockMvc.perform(get("/api/equipment/eq-rej/status"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(20))
                .andExpect(jsonPath("$.effectiveThresholdMinutes").value(120));

        // 历史完整：拒绝与批准各一条
        mockMvc.perform(get("/api/equipment/eq-rej/deferrals"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].deferKey").value("dk1"))
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[1].deferKey").value("dk2"))
                .andExpect(jsonPath("$[1].status").value("APPROVED"));
    }

    // ---------- 保养完成使待审批延期失效 ----------

    @Test
    void maintenance_expiresPendingDeferral() throws Exception {
        register("eq-exp", 100);
        addReading("eq-exp", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 110, 201);
        applyDeferral("eq-exp", "d-1", "dk1", 20, "alice").andExpect(status().isCreated());

        // 保养完成先提交：待审批延期自动失效
        mockMvc.perform(post("/api/equipment/eq-exp/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-1","expectedVersion":2,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        approve("eq-exp", "dk1", "d-2", "bob")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEFERRAL_NOT_PENDING"));

        mockMvc.perform(get("/api/equipment/eq-exp/deferrals"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));

        // 新周期按原始阈值计算，额度归零
        mockMvc.perform(get("/api/equipment/eq-exp/status"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(0))
                .andExpect(jsonPath("$.effectiveThresholdMinutes").value(100))
                .andExpect(jsonPath("$.pendingDeferralKey").doesNotExist());
    }

    // ---------- 幂等 ----------

    @Test
    void idempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-idem", 100);

        // 失败不占键：未 DUE 时申请 409，进入 DUE 后同 requestId 可成功
        applyDeferral("eq-idem", "d-apply", "dk1", 20, "alice")
                .andExpect(status().isConflict());
        addReading("eq-idem", "r-1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        MvcResult applied = applyDeferral("eq-idem", "d-apply", "dk1", 20, "alice")
                .andExpect(status().isCreated())
                .andReturn();

        // 同键同参重放首次结果；同键异参 409
        MvcResult replay = applyDeferral("eq-idem", "d-apply", "dk1", 20, "alice")
                .andExpect(status().isCreated())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(
                applied.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        applyDeferral("eq-idem", "d-apply", "dk1", 25, "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mockMvc.perform(get("/api/equipment/eq-idem/deferrals"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 批准同样幂等：重放同参成功，异参 409
        MvcResult approved = approve("eq-idem", "dk1", "d-appr", "bob")
                .andExpect(status().isOk())
                .andReturn();
        MvcResult approvedReplay = approve("eq-idem", "dk1", "d-appr", "bob")
                .andExpect(status().isOk())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(
                approved.getResponse().getContentAsString(),
                approvedReplay.getResponse().getContentAsString());
        approve("eq-idem", "dk1", "d-appr", "carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mockMvc.perform(get("/api/equipment/eq-idem/status"))
                .andExpect(jsonPath("$.approvedDeferralMinutes").value(20));
    }

    // ---------- 资源不存在 ----------

    @Test
    void notFound_branches() throws Exception {
        applyDeferral("nope", "d-1", "dk1", 10, "alice")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope/deferrals"))
                .andExpect(status().isNotFound());

        register("eq-nf", 100);
        approve("eq-nf", "dk-x", "d-2", "bob")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFERRAL_NOT_FOUND"));
        postAction("/api/equipment/eq-nf/deferrals/dk-x/reject", """
                {"requestId":"d-3","approver":"bob","reason":"不存在"}
                """).andExpect(status().isNotFound());
    }
}
