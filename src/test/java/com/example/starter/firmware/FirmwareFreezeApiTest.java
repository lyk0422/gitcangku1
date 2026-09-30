package com.example.starter.firmware;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 版本正式发布冻结 API 测试（真实 H2 内存库，MODE=MySQL）：
 * 冻结/恢复主流程、409/422 失败分支、旧任务回执与只读查询、审计区间保留、幂等边界与不消耗分桶容量。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareFreezeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM version_freeze_restore");
        jdbc.update("DELETE FROM version_freeze");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM version_freeze_restore");
        jdbc.update("DELETE FROM version_freeze");
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, String from, String to, int ratio)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d}
                """.formatted(requestId, model, from, to, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.releaseId")).longValue();
    }

    private long pullExpectTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.task.taskId")).longValue();
    }

    private long freezeVersion(String requestId, String version, String operator, String reason)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                {"requestId":"%s","version":"%s","operator":"%s","reason":"%s"}
                """.formatted(requestId, version, operator, reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.frozenBy").value(operator))
                .andExpect(jsonPath("$.frozenReason").value(reason))
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.freezeId")).longValue();
    }

    private long requestRestore(String requestId, String version, String operator, String reason)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/freezes/" + version + "/restores")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","operator":"%s","reason":"%s"}
                                """.formatted(requestId, operator, reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedBy").value(operator))
                .andExpect(jsonPath("$.reason").value(reason))
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.restoreId")).longValue();
    }

    private void approve(String requestId, String version, long restoreId, String approver, int httpStatus,
                         String expectedCode) throws Exception {
        var matcher = mockMvc.perform(post("/api/freezes/" + version + "/restores/" + restoreId + "/approve")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"approver\":\"%s\"}".formatted(requestId, approver)))
                .andExpect(status().is(httpStatus));
        if (expectedCode != null) {
            matcher.andExpect(jsonPath("$.code").value(expectedCode));
        }
    }

    @Test
    void 主流程_冻结后新任务409不消耗分桶_旧任务回执入库_异人审批后恢复投放() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0", 1);
        registerDevice("r-d2", "d2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r-rel", "m1", "1.0.0", "2.0.0", 100);
        long oldTaskId = pullExpectTask("r-pull1", "d1");

        long freezeId = freezeVersion("r-fz", "2.0.0", "jia", "发现严重回退缺陷");

        // 冻结后新设备拉取：409，不新建任务（分桶容量不消耗，任务总数仍为 1）
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_VERSION_FROZEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);

        // 冻结后 d2 再次拉取仍 409（409 不占幂等键，恢复后同 requestId 可成功）
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_VERSION_FROZEN"));

        // 已有任务的设备重复拉取：返回原任务而非 409
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(oldTaskId))
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 旧任务回执继续处理并入库：SUCCESS 更新设备版本
        mockMvc.perform(post("/api/tasks/" + oldTaskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 只读查询继续可用
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.tasks[0].toVersion").value("2.0.0"));

        // 创建人甲申请恢复
        long restoreId = requestRestore("r-rs-req", "2.0.0", "jia", "缺陷已修复并回归通过");

        // 冻结人甲自批：422，冻结状态不变
        approve("r-rs-self", "2.0.0", restoreId, "jia", 422, "RESTORE_APPROVER_CONFLICT");
        mockMvc.perform(get("/api/freezes/2.0.0"))
                .andExpect(jsonPath("$.freeze.freezeId").value(freezeId))
                .andExpect(jsonPath("$.freeze.status").value("FROZEN"))
                .andExpect(jsonPath("$.freeze.frozenBy").value("jia"))
                .andExpect(jsonPath("$.freeze.frozenReason").value("发现严重回退缺陷"))
                .andExpect(jsonPath("$.freeze.approvedBy").doesNotExist());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM version_freeze_restore WHERE id = ?", String.class, restoreId))
                .isEqualTo("PENDING");

        // 乙批准：冻结解除
        approve("r-rs-ok", "2.0.0", restoreId, "yi", 200, null);
        mockMvc.perform(get("/api/freezes/2.0.0"))
                .andExpect(jsonPath("$.freeze.status").value("RESUMED"))
                .andExpect(jsonPath("$.freeze.approvedBy").value("yi"))
                .andExpect(jsonPath("$.restores[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.restores[0].reviewedBy").value("yi"));

        // 恢复后 d2 可用原先失败的 requestId 成功投放（证明 409 未占键、未消耗分桶）
        long newTaskId = pullExpectTask("r-pull2", "d2");
        assertThat(newTaskId).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);
    }

    @Test
    void 冻结_重复冻结409_不匹配设备静默无任务_冻结记录字段完整() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0", 50);
        registerDevice("r-d2", "d2", "m1", "9.9.9", 1);
        createRelease("r-rel", "m1", "1.0.0", "2.0.0", 10);
        freezeVersion("r-fz1", "2.0.0", "jia", "原因A");

        // 同版本重复冻结：409
        mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                {"requestId":"r-fz2","version":"2.0.0","operator":"bing","reason":"原因B"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_FROZEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM version_freeze", Long.class)).isEqualTo(1);

        // 冻结记录含创建人、原因与 UTC 时刻
        mockMvc.perform(get("/api/freezes/2.0.0"))
                .andExpect(jsonPath("$.freeze.frozenBy").value("jia"))
                .andExpect(jsonPath("$.freeze.frozenReason").value("原因A"))
                .andExpect(jsonPath("$.freeze.frozenAtUtc").isString());
        String frozenAt = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(get("/api/freezes/2.0.0")).andReturn().getResponse().getContentAsString(),
                "$.freeze.frozenAtUtc");
        assertThat(frozenAt).endsWith("Z");

        // 分桶不命中的设备：维持原有静默语义（无任务），不是 409
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        // 当前版本不匹配：静默无任务
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
    }

    @Test
    void 恢复失败分支_无生效冻结409_重复申请422_审批不存在404_版本不符409_失败不清除审计() throws Exception {
        // 从未冻结的版本申请恢复：409
        mockMvc.perform(post("/api/freezes/3.0.0/restores").contentType("application/json")
                        .content("{\"requestId\":\"r-r1\",\"operator\":\"jia\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREEZE_NOT_ACTIVE"));

        freezeVersion("r-fz", "2.0.0", "jia", "原因A");
        long restoreId = requestRestore("r-r2", "2.0.0", "jia", "申请一");

        // 已有待审批申请时再次申请：422
        mockMvc.perform(post("/api/freezes/2.0.0/restores").contentType("application/json")
                        .content("{\"requestId\":\"r-r3\",\"operator\":\"jia\",\"reason\":\"申请二\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESTORE_ALREADY_PENDING"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM version_freeze_restore", Long.class)).isEqualTo(1);

        // 审批不存在的申请：404
        approve("r-a1", "2.0.0", restoreId + 9999, "yi", 404, "RESTORE_NOT_FOUND");

        // URL 版本与申请所属冻结版本不一致：409
        approve("r-a2", "9.9.9", restoreId, "yi", 409, "FREEZE_VERSION_MISMATCH");

        // 多次失败后：冻结仍 FROZEN，原创建人/原因/时刻与唯一一条申请审计均保留
        mockMvc.perform(get("/api/freezes/2.0.0"))
                .andExpect(jsonPath("$.freeze.status").value("FROZEN"))
                .andExpect(jsonPath("$.freeze.frozenBy").value("jia"))
                .andExpect(jsonPath("$.freeze.frozenReason").value("原因A"))
                .andExpect(jsonPath("$.restores.length()").value(1))
                .andExpect(jsonPath("$.restores[0].status").value("PENDING"))
                .andExpect(jsonPath("$.restores[0].reason").value("申请一"));
    }

    @Test
    void 幂等_冻结与审批同键重放首次状态_换版本或动作409_422不占键() throws Exception {
        createRelease("r-rel", "m1", "1.0.0", "2.0.0", 100);
        String first = mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                {"requestId":"same-id","version":"2.0.0","operator":"jia","reason":"冻结原因"}
                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 同键同参重放：响应一致，不新增冻结
        String replay = mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                {"requestId":"same-id","version":"2.0.0","operator":"jia","reason":"冻结原因"}
                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM version_freeze", Long.class)).isEqualTo(1);

        // 同键换版本：409
        mockMvc.perform(post("/api/freezes").contentType("application/json").content("""
                {"requestId":"same-id","version":"3.0.0","operator":"jia","reason":"冻结原因"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 同键换动作（拿冻结 requestId 去申请恢复）：409
        mockMvc.perform(post("/api/freezes/2.0.0/restores").contentType("application/json")
                        .content("{\"requestId\":\"same-id\",\"operator\":\"jia\",\"reason\":\"恢复\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        long restoreId = requestRestore("r-req", "2.0.0", "jia", "恢复原因");

        // 同人审批 422：不占键，随后同 requestId 换异人审批成功
        approve("r-ap", "2.0.0", restoreId, "jia", 422, "RESTORE_APPROVER_CONFLICT");
        approve("r-ap", "2.0.0", restoreId, "yi", 200, null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM version_freeze_restore", Long.class)).isEqualTo(1);

        // 审批同键重放：仍为首次成功状态
        approve("r-ap", "2.0.0", restoreId, "yi", 200, null);
        mockMvc.perform(get("/api/freezes/2.0.0"))
                .andExpect(jsonPath("$.freeze.status").value("RESUMED"))
                .andExpect(jsonPath("$.restores.length()").value(1));
    }

    @Test
    void 恢复保留冻结区间_可再次冻结_未冻结版本查询为空() throws Exception {
        long firstFreezeId = freezeVersion("r-fz1", "2.0.0", "jia", "第一次冻结");
        long restoreId = requestRestore("r-rs1", "2.0.0", "jia", "恢复");
        approve("r-ap1", "2.0.0", restoreId, "yi", 200, null);

        // 冻结区间不被抹除：FROZEN 时的创建人/原因/时刻保留，新增审批人与恢复时刻
        mockMvc.perform(get("/api/freezes/2.0.0"))
                .andExpect(jsonPath("$.freeze.freezeId").value(firstFreezeId))
                .andExpect(jsonPath("$.freeze.status").value("RESUMED"))
                .andExpect(jsonPath("$.freeze.frozenBy").value("jia"))
                .andExpect(jsonPath("$.freeze.frozenReason").value("第一次冻结"))
                .andExpect(jsonPath("$.freeze.approvedBy").value("yi"))
                .andExpect(jsonPath("$.freeze.restoredAtUtc").isString());

        // 恢复后可对同版本再次冻结；查询展示最近一条
        long secondFreezeId = freezeVersion("r-fz2", "2.0.0", "bing", "第二次冻结");
        assertThat(secondFreezeId).isNotEqualTo(firstFreezeId);
        mockMvc.perform(get("/api/freezes/2.0.0"))
                .andExpect(jsonPath("$.freeze.freezeId").value(secondFreezeId))
                .andExpect(jsonPath("$.freeze.status").value("FROZEN"))
                .andExpect(jsonPath("$.freeze.frozenBy").value("bing"));
        // 历史两条区间均保留
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM version_freeze", Long.class)).isEqualTo(2);

        // 从未冻结的版本：freeze 为 null，审计为空
        mockMvc.perform(get("/api/freezes/4.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("4.0.0"))
                .andExpect(jsonPath("$.freeze").doesNotExist())
                .andExpect(jsonPath("$.restores.length()").value(0));
    }
}
