package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 版本正式发布冻结 API 测试（H2 内存库，MODE=MySQL）：冻结/申请/审批主流程、
 * 冻结期建任务 409 不占容量、旧任务回执继续入库、422 失败不改变冻结状态与审计、
 * requestId 重放与换版本/换动作 409、冻结区间保留与只读查询。时钟使用可控 Clock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareFreezeTest {

    static final Instant FREEZE_AT = Instant.parse("2026-09-30T14:00:00Z");
    static final Instant REQUEST_AT = Instant.parse("2026-09-30T15:00:00Z");
    static final Instant APPROVE_AT = Instant.parse("2026-09-30T16:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_freeze_event");
        jdbc.update("DELETE FROM release_freeze_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        Mockito.reset(clock);
        Mockito.when(clock.instant()).thenReturn(FREEZE_AT, REQUEST_AT, APPROVE_AT);
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String model, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, model, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, String toVersion, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"%s","ratio":%d}
                """.formatted(requestId, model, toVersion, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullExpectTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").exists())
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private long freeze(String requestId, String version, String operator, String reason) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                {"requestId":"%s","version":"%s","operator":"%s","reason":"%s"}
                """.formatted(requestId, version, operator, reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andReturn();
        return idOf(result, "$.freezeId");
    }

    @Test
    void 主流程_冻结后新任务409不占容量_旧任务回执入库_乙审批后恢复投放() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        registerDevice("r-d2", "d2", "m1", 2);
        createRelease("r-r1", "m1", "2.0.0", 100);
        long oldTaskId = pullExpectTask("r-p1", "d1");

        // 甲冻结 V，记录创建人、原因与 UTC 生效时刻
        MvcResult frozen = mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                        {"requestId":"r-f1","version":"2.0.0","operator":"alice","reason":"发现严重回滚缺陷"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freezeId").isNumber())
                .andExpect(jsonPath("$.version").value("2.0.0"))
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                .andExpect(jsonPath("$.reason").value("发现严重回滚缺陷"))
                .andExpect(jsonPath("$.frozenAtUtc").value("2026-09-30T14:00:00Z"))
                .andExpect(jsonPath("$.resumeRequestedBy").doesNotExist())
                .andExpect(jsonPath("$.approvedBy").doesNotExist())
                .andReturn();
        long freezeId = idOf(frozen, "$.freezeId");

        // 冻结生效后为新版本新建设备拉取：409，任务不落库、不占分桶容量
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_FROZEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);

        // 冻结前已有任务的回执继续入库，SUCCESS 仍更新设备版本
        mockMvc.perform(post("/api/tasks/" + oldTaskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        // 已有任务设备重复拉取仍返回原任务，不判 409
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(oldTaskId))
                .andExpect(jsonPath("$.task.status").value("SUCCESS"));

        // 甲申请恢复
        mockMvc.perform(post("/api/freezes/" + freezeId + "/resume-requests")
                        .contentType("application/json").content("""
                                {"requestId":"r-rr1","operator":"alice","reason":"缺陷已修复并回归"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.resumeRequestedBy").value("alice"))
                .andExpect(jsonPath("$.resumeReason").value("缺陷已修复并回归"))
                .andExpect(jsonPath("$.resumeRequestedAtUtc").value("2026-09-30T15:00:00Z"));

        // 同人（冻结创建人甲）审批：422，冻结状态不变
        mockMvc.perform(post("/api/freezes/" + freezeId + "/approvals")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-ap1\",\"approver\":\"alice\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
        mockMvc.perform(get("/api/freezes/active").param("version", "2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.approvedBy").doesNotExist());
        // 422 后仍不能新建正式任务
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2b\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_FROZEN"));

        // 乙审批：恢复生效
        mockMvc.perform(post("/api/freezes/" + freezeId + "/approvals")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-ap2\",\"approver\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUMED"))
                .andExpect(jsonPath("$.approvedBy").value("bob"))
                .andExpect(jsonPath("$.resumedAtUtc").value("2026-09-30T16:00:00Z"))
                .andExpect(jsonPath("$.frozenAtUtc").value("2026-09-30T14:00:00Z"))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                .andExpect(jsonPath("$.reason").value("发现严重回滚缺陷"));

        // 恢复后可再次投放，d2 正式任务建立
        pullExpectTask("r-p3", "d2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 查询：无当前生效冻结；历史冻结区间与审计流水保留
        mockMvc.perform(get("/api/freezes/active").param("version", "2.0.0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FREEZE_NOT_FOUND"));
        mockMvc.perform(get("/api/freezes").param("version", "2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].status").value("RESUMED"))
                .andExpect(jsonPath("$.records[0].frozenAtUtc").value("2026-09-30T14:00:00Z"))
                .andExpect(jsonPath("$.records[0].resumedAtUtc").value("2026-09-30T16:00:00Z"))
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[0].eventType").value("FROZEN"))
                .andExpect(jsonPath("$.events[0].operator").value("alice"))
                .andExpect(jsonPath("$.events[1].eventType").value("RESUME_REQUESTED"))
                .andExpect(jsonPath("$.events[1].operator").value("alice"))
                .andExpect(jsonPath("$.events[2].eventType").value("RESUME_APPROVED"))
                .andExpect(jsonPath("$.events[2].operator").value("bob"));
    }

    @Test
    void 冻结_重复冻结409_恢复后可再次冻结_无申请审批422且状态审计不变() throws Exception {
        long first = freeze("r-f1", "3.0.0", "alice", "紧急止血");

        // 同版本已有生效冻结：409
        mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                        {"requestId":"r-f2","version":"3.0.0","operator":"bob","reason":"再来一次"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREEZE_ALREADY_ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_freeze_record WHERE version = '3.0.0'",
                Long.class)).isEqualTo(1);

        // 未申请恢复直接审批：422，冻结状态与审计不变
        mockMvc.perform(post("/api/freezes/" + first + "/approvals").contentType("application/json")
                        .content("{\"requestId\":\"r-ap0\",\"approver\":\"bob\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESUME_NOT_REQUESTED"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM release_freeze_record WHERE id = ?", String.class, first))
                .isEqualTo("FROZEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_freeze_event WHERE freeze_id = ?",
                Long.class, first)).isEqualTo(1);

        // 走完申请+乙审批恢复
        mockMvc.perform(post("/api/freezes/" + first + "/resume-requests").contentType("application/json")
                        .content("{\"requestId\":\"r-rr1\",\"operator\":\"alice\",\"reason\":\"修复完成\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/freezes/" + first + "/approvals").contentType("application/json")
                        .content("{\"requestId\":\"r-ap1\",\"approver\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUMED"));

        // 恢复后同版本可再次冻结，生成新记录；旧冻结区间仍保留
        long second = freeze("r-f3", "3.0.0", "carol", "回归发现新问题");
        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(get("/api/freezes").param("version", "3.0.0"))
                .andExpect(jsonPath("$.current.freezeId").value(second))
                .andExpect(jsonPath("$.records.length()").value(2));

        // 已恢复记录不能再申请/审批：409
        mockMvc.perform(post("/api/freezes/" + first + "/resume-requests").contentType("application/json")
                        .content("{\"requestId\":\"r-rr2\",\"operator\":\"alice\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREEZE_ALREADY_RESUMED"));
        mockMvc.perform(post("/api/freezes/" + first + "/approvals").contentType("application/json")
                        .content("{\"requestId\":\"r-ap2\",\"approver\":\"bob\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREEZE_ALREADY_RESUMED"));

        // 不存在的冻结记录：404
        mockMvc.perform(post("/api/freezes/9999/resume-requests").contentType("application/json")
                        .content("{\"requestId\":\"r-rr404\",\"operator\":\"alice\",\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/freezes/9999/approvals").contentType("application/json")
                        .content("{\"requestId\":\"r-ap404\",\"approver\":\"bob\"}"))
                .andExpect(status().isNotFound());

        // 重复申请恢复：409
        mockMvc.perform(post("/api/freezes/" + second + "/resume-requests").contentType("application/json")
                        .content("{\"requestId\":\"r-rr3\",\"operator\":\"alice\",\"reason\":\"再次申请\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/freezes/" + second + "/resume-requests").contentType("application/json")
                        .content("{\"requestId\":\"r-rr4\",\"operator\":\"dave\",\"reason\":\"又申请\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESUME_ALREADY_REQUESTED"));
    }

    @Test
    void 幂等_冻结重放快照_换版本换动作409_422失败不占键_409不占键() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                        {"requestId":"rid-f","version":"4.0.0","operator":"alice","reason":"原因A"}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();
        long freezeId = idOf(first, "$.freezeId");

        // 同键同参重放：返回首次快照
        MvcResult replay = mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                        {"requestId":"rid-f","version":"4.0.0","operator":"alice","reason":"原因A"}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_freeze_record WHERE version = '4.0.0'",
                Long.class)).isEqualTo(1);

        // 同键换版本（异参）：409
        mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                        {"requestId":"rid-f","version":"5.0.0","operator":"alice","reason":"原因A"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 同键换动作（冻结 requestId 拿去申请恢复）：409
        mockMvc.perform(post("/api/freezes/" + freezeId + "/resume-requests")
                        .contentType("application/json").content("""
                                {"requestId":"rid-f","operator":"alice","reason":"原因A"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 422 失败不占键：同一 requestId 先同人审批失败，换乙审批成功
        mockMvc.perform(post("/api/freezes/" + freezeId + "/resume-requests")
                        .contentType("application/json").content("""
                                {"requestId":"rid-r","operator":"alice","reason":"已修复"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/freezes/" + freezeId + "/approvals").contentType("application/json")
                        .content("{\"requestId\":\"rid-a\",\"approver\":\"alice\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/freezes/" + freezeId + "/approvals").contentType("application/json")
                        .content("{\"requestId\":\"rid-a\",\"approver\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESUMED"));

        // 冻结期 409 不占键：同 requestId 在冻结期拉取失败，恢复后重放成功建任务
        registerDevice("r-d1", "d9", "m9", 1);
        createRelease("r-r9", "m9", "9.0.0", 100);
        long freeze9 = freeze("r-f9", "9.0.0", "alice", "暂停");
        mockMvc.perform(post("/api/devices/d9/pull").contentType("application/json")
                        .content("{\"requestId\":\"rid-pull\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/freezes/" + freeze9 + "/resume-requests").contentType("application/json")
                        .content("{\"requestId\":\"rid-r9\",\"operator\":\"alice\",\"reason\":\"ok\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/freezes/" + freeze9 + "/approvals").contentType("application/json")
                        .content("{\"requestId\":\"rid-a9\",\"approver\":\"bob\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d9/pull").contentType("application/json")
                        .content("{\"requestId\":\"rid-pull\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));
    }

    @Test
    void 查询与校验_无生效冻结404_历史为空_参数缺失400() throws Exception {
        mockMvc.perform(get("/api/freezes/active").param("version", "8.8.8"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/freezes").param("version", "8.8.8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("8.8.8"))
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.records.length()").value(0))
                .andExpect(jsonPath("$.events.length()").value(0));

        mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                        {"requestId":"r1","version":"","operator":"alice","reason":"x"}
                        """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                        {"requestId":"r2","version":"6.0.0","operator":"","reason":"x"}
                        """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/freezes/1/resume-requests").contentType("application/json").content("""
                        {"requestId":"r3","operator":"alice","reason":""}
                        """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/freezes/1/approvals").contentType("application/json").content("""
                        {"requestId":"r4","approver":""}
                        """))
                .andExpect(status().isBadRequest());
    }
}
