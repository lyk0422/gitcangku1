package com.example.starter.firmware;

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
 * 多跳版本回退 API 主流程与失败分支测试（H2 内存库，MODE=MySQL）：
 * 反向路径构造、逐跳派发门控、回执切换版本、暂停/恢复、取消、幂等与只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareRollbackApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollback_plan_pause_record");
        jdbc.update("DELETE FROM rollback_hop_task");
        jdbc.update("DELETE FROM rollback_plan_hop");
        jdbc.update("DELETE FROM rollback_plan_device");
        jdbc.update("DELETE FROM rollback_plan");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String model, String version)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":1}
                """.formatted(requestId, deviceId, model, version)))
                .andExpect(status().isOk());
    }

    /**
     * 让设备完成一次 from->to 的投放并取消发布单，返回发布单ID。
     */
    private long completeRollout(String keyPrefix, String deviceId, String model,
                                 String from, String to) throws Exception {
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s-rel","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":100}
                """.formatted(keyPrefix, model, from, to)))
                .andExpect(status().isOk())
                .andReturn();
        long releaseId = idOf(release, "$.releaseId");
        MvcResult pull = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s-pull\"}".formatted(keyPrefix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s-rc\",\"result\":\"SUCCESS\"}".formatted(keyPrefix)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"%s-cancel\"}".formatted(keyPrefix)))
                .andExpect(status().isOk());
        return releaseId;
    }

    private long createPlan(String requestId, String planKey, long sourceReleaseId,
                            String targetVersion, String deviceIdsJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/rollback-plans").contentType("application/json")
                        .content("""
                                {"requestId":"%s","planKey":"%s","sourceReleaseId":%d,"targetVersion":"%s",
                                "deviceIds":%s,"sampleFloor":2,"failureThresholdPercent":50}
                                """.formatted(requestId, planKey, sourceReleaseId, targetVersion, deviceIdsJson)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.planId");
    }

    private MvcResult dispatch(long planId, String requestId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/rollback-plans/" + planId + "/dispatch")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"deviceId\":\"%s\"}".formatted(requestId, deviceId)))
                .andReturn();
    }

    private String receiptKeyOf(MvcResult dispatchResult) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(dispatchResult.getResponse().getContentAsString(),
                "$.hopTask.receiptKey");
    }

    private long hopTaskIdOf(MvcResult dispatchResult) throws Exception {
        return idOf(dispatchResult, "$.hopTask.hopTaskId");
    }

    private void receipt(long hopTaskId, String requestId, String receiptKey, String result)
            throws Exception {
        mockMvc.perform(post("/api/rollback-plans/hop-tasks/" + hopTaskId + "/receipt")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","receiptKey":"%s","result":"%s"}
                                """.formatted(requestId, receiptKey, result)))
                .andExpect(status().isOk());
    }

    @Test
    void 主流程_两跳回退_逐跳门控_全部到达后COMPLETED() throws Exception {
        // 设备 d1 经历 1.0.0 -> 2.0.0 -> 3.0.0 两次投放
        registerDevice("r-d1", "d1", "m1", "1.0.0");
        long rel1 = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");
        long rel2 = completeRollout("k2", "d1", "m1", "2.0.0", "3.0.0");

        // 针对已结束的 rel2 提交回退到 1.0.0，路径应为 3.0.0->2.0.0->1.0.0（2跳）
        MvcResult created = mockMvc.perform(post("/api/rollback-plans").contentType("application/json")
                        .content("""
                                {"requestId":"r-plan","planKey":"pk-1","sourceReleaseId":%d,"targetVersion":"1.0.0",
                                "deviceIds":["d1"],"sampleFloor":2,"failureThresholdPercent":50}
                                """.formatted(rel2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planKey").value("pk-1"))
                .andExpect(jsonPath("$.sourceReleaseId").value(rel2))
                .andExpect(jsonPath("$.targetVersion").value("1.0.0"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monitorRound").value(1))
                .andReturn();
        long planId = idOf(created, "$.planId");
        assertThat(rel1).isNotEqualTo(rel2);

        // 明细：两跳冻结定义，hop1 来源 rel2，hop2 来源 rel1
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.devices.length()").value(1))
                .andExpect(jsonPath("$.devices[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.devices[0].hopCount").value(2))
                .andExpect(jsonPath("$.devices[0].hops.length()").value(2))
                .andExpect(jsonPath("$.devices[0].hops[0].hopIndex").value(1))
                .andExpect(jsonPath("$.devices[0].hops[0].expectedVersion").value("3.0.0"))
                .andExpect(jsonPath("$.devices[0].hops[0].targetVersion").value("2.0.0"))
                .andExpect(jsonPath("$.devices[0].hops[0].sourceReleaseId").value(rel2))
                .andExpect(jsonPath("$.devices[0].hops[1].hopIndex").value(2))
                .andExpect(jsonPath("$.devices[0].hops[1].expectedVersion").value("2.0.0"))
                .andExpect(jsonPath("$.devices[0].hops[1].targetVersion").value("1.0.0"))
                .andExpect(jsonPath("$.devices[0].hops[1].sourceReleaseId").value(rel1))
                .andExpect(jsonPath("$.devices[0].hops[0].tasks.length()").value(0))
                .andExpect(jsonPath("$.rounds.length()").value(0))
                .andExpect(jsonPath("$.pauses.length()").value(0));

        // 第一跳派发：冻结版本与回执凭证
        MvcResult dispatch1 = dispatch(planId, "r-disp1", "d1");
        long hop1Task = hopTaskIdOf(dispatch1);
        String key1 = receiptKeyOf(dispatch1);
        mockMvc.perform(post("/api/rollback-plans/" + planId + "/dispatch")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-disp1b\",\"deviceId\":\"d1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hopTask.hopTaskId").value(hop1Task))
                .andExpect(jsonPath("$.hopTask.hopIndex").value(1))
                .andExpect(jsonPath("$.hopTask.roundNo").value(1))
                .andExpect(jsonPath("$.hopTask.expectedVersion").value("3.0.0"))
                .andExpect(jsonPath("$.hopTask.targetVersion").value("2.0.0"))
                .andExpect(jsonPath("$.hopTask.sourceReleaseId").value(rel2))
                .andExpect(jsonPath("$.hopTask.status").value("PENDING"))
                .andExpect(jsonPath("$.hopTask.receiptKey").value(key1));
        assertThat(key1).startsWith("rk");

        // 第一跳未成功前不会派发第二跳：重复派发仍返回第一跳任务
        receipt(hop1Task, "r-rc1", key1, "SUCCESS");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 第二跳派发并成功
        MvcResult dispatch2 = dispatch(planId, "r-disp2", "d1");
        long hop2Task = hopTaskIdOf(dispatch2);
        String key2 = receiptKeyOf(dispatch2);
        assertThat(key2).isNotEqualTo(key1);
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(jsonPath("$.devices[0].hops[1].tasks.length()").value(1))
                .andExpect(jsonPath("$.devices[0].hops[1].tasks[0].expectedVersion").value("2.0.0"))
                .andExpect(jsonPath("$.devices[0].hops[1].tasks[0].targetVersion").value("1.0.0"));
        receipt(hop2Task, "r-rc2", key2, "SUCCESS");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 全部设备到达目标：COMPLETED，轮次统计与逐跳历史可查
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(jsonPath("$.plan.status").value("COMPLETED"))
                .andExpect(jsonPath("$.plan.roundSuccess").value(2))
                .andExpect(jsonPath("$.plan.roundFailed").value(0))
                .andExpect(jsonPath("$.devices[0].hops[0].tasks[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.devices[0].hops[1].tasks[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.rounds.length()").value(1))
                .andExpect(jsonPath("$.rounds[0].roundNo").value(1))
                .andExpect(jsonPath("$.rounds[0].success").value(2))
                .andExpect(jsonPath("$.rounds[0].failed").value(0));

        // 完结后设备占用释放：可参与新投放
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-rel2","model":"m1","fromVersion":"1.0.0","toVersion":"9.9.9","ratio":100}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull-new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 完结后不再派发
        mockMvc.perform(post("/api/rollback-plans/" + planId + "/dispatch")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-disp3\",\"deviceId\":\"d1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_COMPLETED"));
    }

    @Test
    void 创建校验_来源未结束_缺路径_版本不一致_历史断裂_超长_整单409() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0");
        registerDevice("r-d2", "d2", "m1", "1.0.0");
        registerDevice("r-d3", "d3", "m2", "1.0.0");

        // 来源投放不存在：404
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-c0","planKey":"pk-x","sourceReleaseId":9999,"targetVersion":"1.0.0",
                "deviceIds":["d1"]}
                """)).andExpect(status().isNotFound());

        // 来源投放未结束（ACTIVE）：409
        MvcResult active = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-rel-a","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk()).andReturn();
        long activeId = idOf(active, "$.releaseId");
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-c1","planKey":"pk-a","sourceReleaseId":%d,"targetVersion":"1.0.0",
                "deviceIds":["d1"]}
                """.formatted(activeId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_RELEASE_NOT_ENDED"));
        mockMvc.perform(post("/api/releases/" + activeId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel-a\"}"))
                .andExpect(status().isOk());

        // 设备无投放历史，缺路径：409，且整单不落数据
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-c2","planKey":"pk-b","sourceReleaseId":%d,"targetVersion":"0.9.0",
                "deviceIds":["d1"]}
                """.formatted(activeId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NO_PATH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan", Long.class)).isZero();

        // 设备型号与来源投放不一致：409
        long rel2 = completeRollout("k2", "d1", "m1", "1.0.0", "2.0.0");
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-c3","planKey":"pk-c","sourceReleaseId":%d,"targetVersion":"1.0.0",
                "deviceIds":["d3"]}
                """.formatted(rel2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_MODEL_MISMATCH"));

        // 目标版本不在历史中，缺路径：409
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-c4","planKey":"pk-d","sourceReleaseId":%d,"targetVersion":"0.1.0",
                "deviceIds":["d1"]}
                """.formatted(rel2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NO_PATH"));

        // 目标版本即当前版本：409
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-c5","planKey":"pk-e","sourceReleaseId":%d,"targetVersion":"2.0.0",
                "deviceIds":["d1"]}
                """.formatted(rel2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NO_PATH"));

        // 当前版本被篡改与历史不一致：409（d2 已完成 1.0.0 -> 2.0.0）
        long relD2 = completeRollout("kd2", "d2", "m1", "1.0.0", "2.0.0");
        jdbc.update("UPDATE device SET current_version = '9.9.9' WHERE device_id = 'd2'");
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-c6","planKey":"pk-f","sourceReleaseId":%d,"targetVersion":"1.0.0",
                "deviceIds":["d2"]}
                """.formatted(relD2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_VERSION_MISMATCH"));

        // 历史代次断裂（人为插入断代成功任务 5.0.0 -> 6.0.0，与既有 1.0.0 -> 2.0.0 不连续）：409
        jdbc.update("INSERT INTO release_order (version, model, from_version, to_version, ratio, status,"
                + " sample_floor, failure_threshold_percent, monitor_round)"
                + " VALUES (1, 'm1', '5.0.0', '6.0.0', 100, 'CANCELLED', 2, 100, 1)");
        Long brokenRelease = jdbc.queryForObject(
                "SELECT id FROM release_order WHERE to_version = '6.0.0'", Long.class);
        jdbc.update("INSERT INTO rollout_task (release_id, device_id, status, first_result)"
                + " VALUES (?, 'd2', 'SUCCESS', 'SUCCESS')", brokenRelease);
        jdbc.update("UPDATE device SET current_version = '6.0.0' WHERE device_id = 'd2'");
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-c7","planKey":"pk-g","sourceReleaseId":%d,"targetVersion":"5.0.0",
                "deviceIds":["d2"]}
                """.formatted(brokenRelease)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_HISTORY_INCONSISTENT"));

        // 路径超过5跳：409
        registerDevice("r-d9", "d9", "m9", "1.0");
        String[] versions = {"1.0", "2.0", "3.0", "4.0", "5.0", "6.0", "7.0"};
        long lastRelease = 0;
        for (int i = 0; i < versions.length - 1; i++) {
            lastRelease = completeRollout("k9-" + i, "d9", "m9", versions[i], versions[i + 1]);
        }
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-c8","planKey":"pk-h","sourceReleaseId":%d,"targetVersion":"1.0",
                "deviceIds":["d9"]}
                """.formatted(lastRelease)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_PATH_TOO_LONG"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan", Long.class)).isZero();
    }

    @Test
    void 创建幂等_同键同参换序重放_异参409_planKey全局唯一_失败不占键() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0");
        registerDevice("r-d2", "d2", "m1", "1.0.0");
        long rel = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");
        completeRollout("k2", "d2", "m1", "1.0.0", "2.0.0");

        // 失败不占键：先以缺路径触发 409，再用同 requestId 修正参数成功
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-p","planKey":"pk-1","sourceReleaseId":%d,"targetVersion":"0.0.1",
                "deviceIds":["d1","d2"]}
                """.formatted(rel)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NO_PATH"));
        MvcResult created = mockMvc.perform(post("/api/rollback-plans").contentType("application/json")
                        .content("""
                                {"requestId":"r-p","planKey":"pk-1","sourceReleaseId":%d,"targetVersion":"1.0.0",
                                "deviceIds":["d1","d2"]}
                                """.formatted(rel)))
                .andExpect(status().isOk())
                .andReturn();
        String firstBody = created.getResponse().getContentAsString();
        long planId = idOf(created, "$.planId");

        // 同 requestId 同参（设备集合换序）重放：响应一致，不重复建单
        MvcResult replay = mockMvc.perform(post("/api/rollback-plans").contentType("application/json")
                        .content("""
                                {"requestId":"r-p","planKey":"pk-1","sourceReleaseId":%d,"targetVersion":"1.0.0",
                                "deviceIds":["d2","d1"]}
                                """.formatted(rel)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan", Long.class)).isEqualTo(1);

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-p","planKey":"pk-1","sourceReleaseId":%d,"targetVersion":"1.0.0",
                "deviceIds":["d1"]}
                """.formatted(rel)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // planKey 全局唯一：不同 requestId 相同 planKey 409
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-p2","planKey":"pk-1","sourceReleaseId":%d,"targetVersion":"1.0.0",
                "deviceIds":["d1"]}
                """.formatted(rel)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_KEY_EXISTS"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_plan", Long.class)).isEqualTo(1);
        assertThat(planId).isPositive();
    }

    @Test
    void 回执_凭证校验_失败保留版本_重复与改结果_取消后拒收() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0");
        long rel = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");
        long planId = createPlan("r-p", "pk-1", rel, "1.0.0", "[\"d1\"]");
        MvcResult dispatch = dispatch(planId, "r-disp", "d1");
        long hopTask = hopTaskIdOf(dispatch);
        String key = receiptKeyOf(dispatch);

        // receiptKey 不匹配：409
        mockMvc.perform(post("/api/rollback-plans/hop-tasks/" + hopTask + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-rc0\",\"receiptKey\":\"rk-bogus\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_KEY_MISMATCH"));

        // 任务不存在：404
        mockMvc.perform(post("/api/rollback-plans/hop-tasks/9999/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-rc9\",\"receiptKey\":\"rk-x\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());

        // FAILED：保留设备版本
        receipt(hopTask, "r-rc1", key, "FAILED");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 同结果重复回执成功且不重复计数
        receipt(hopTask, "r-rc1-dup", key, "FAILED");
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(jsonPath("$.plan.roundFailed").value(1))
                .andExpect(jsonPath("$.plan.roundSuccess").value(0));

        // 改结果：409
        mockMvc.perform(post("/api/rollback-plans/hop-tasks/" + hopTask + "/receipt")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"r-rc2","receiptKey":"%s","result":"SUCCESS"}
                                """.formatted(key)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_RESULT_CONFLICT"));

        // 取消计划：未终结任务转 CANCELLED，设备占用释放，不再接收新回执
        mockMvc.perform(post("/api/rollback-plans/" + planId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/rollback-plans/" + planId + "/dispatch")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-disp2\",\"deviceId\":\"d1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_CANCELLED"));

        // 取消后设备可参与新投放
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-rel2","model":"m1","fromVersion":"2.0.0","toVersion":"3.0.0","ratio":100}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));
    }

    @Test
    void 取消_进行中计划_未回执任务转CANCELLED_后到回执409() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0");
        long rel = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");
        long planId = createPlan("r-p", "pk-1", rel, "1.0.0", "[\"d1\"]");
        MvcResult dispatch = dispatch(planId, "r-disp", "d1");
        long hopTask = hopTaskIdOf(dispatch);
        String key = receiptKeyOf(dispatch);

        mockMvc.perform(post("/api/rollback-plans/" + planId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 取消后新回执 409，设备版本不变
        mockMvc.perform(post("/api/rollback-plans/hop-tasks/" + hopTask + "/receipt")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"r-rc1","receiptKey":"%s","result":"SUCCESS"}
                                """.formatted(key)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 重复取消幂等
        mockMvc.perform(post("/api/rollback-plans/" + planId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 取消后恢复 409
        mockMvc.perform(post("/api/rollback-plans/" + planId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res\",\"reason\":\"太晚\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_CANCELLED"));

        // 计划不存在：404
        mockMvc.perform(get("/api/rollback-plans/9999")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rollback-plans/9999/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c9\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 暂停与恢复_该跳失败率达阈值暂停_恢复新轮次只含未成功设备() throws Exception {
        // 三台设备均完成 1.0.0 -> 2.0.0
        for (int i = 1; i <= 3; i++) {
            registerDevice("r-d" + i, "d" + i, "m1", "1.0.0");
        }
        long rel = 0;
        for (int i = 1; i <= 3; i++) {
            rel = completeRollout("k" + i, "d" + i, "m1", "1.0.0", "2.0.0");
        }
        // floor=2, threshold=50
        long planId = createPlan("r-p", "pk-1", rel, "1.0.0", "[\"d1\",\"d2\",\"d3\"]");

        long t1 = hopTaskIdOf(dispatch(planId, "r-disp1", "d1"));
        String k1 = receiptKeyOf(dispatch(planId, "r-disp1b", "d1"));
        long t2 = hopTaskIdOf(dispatch(planId, "r-disp2", "d2"));
        String k2 = receiptKeyOf(dispatch(planId, "r-disp2b", "d2"));

        // 样本1：未达下限
        receipt(t1, "r-rc1", k1, "FAILED");
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(jsonPath("$.plan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan.roundFailed").value(1));

        // 样本2：失败率 2/2 >= 50%，当次回执事务内原子暂停
        receipt(t2, "r-rc2", k2, "FAILED");
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(jsonPath("$.plan.status").value("PAUSED"))
                .andExpect(jsonPath("$.plan.pausedHopIndex").value(1))
                .andExpect(jsonPath("$.plan.roundFailed").value(2))
                .andExpect(jsonPath("$.pauses.length()").value(1))
                .andExpect(jsonPath("$.pauses[0].monitorRound").value(1))
                .andExpect(jsonPath("$.pauses[0].hopIndex").value(1))
                .andExpect(jsonPath("$.pauses[0].triggerHopTaskId").value(t2))
                .andExpect(jsonPath("$.pauses[0].successCount").value(0))
                .andExpect(jsonPath("$.pauses[0].failedCount").value(2))
                .andExpect(jsonPath("$.pauses[0].pausedAtUtc").isString());

        // PAUSED：尚未派发设备停止派发
        mockMvc.perform(post("/api/rollback-plans/" + planId + "/dispatch")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-disp3\",\"deviceId\":\"d3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_PAUSED"));

        // ACTIVE 以外的恢复校验：PAUSED 才可恢复（错误状态码路径）
        mockMvc.perform(post("/api/rollback-plans/" + planId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res\",\"reason\":\"已修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.pausedHopIndex").doesNotExist());

        // 恢复后新轮次：未成功设备重新派发该跳（新任务、新凭证、roundNo=2）
        MvcResult re1 = dispatch(planId, "r-disp1r", "d1");
        long t1r = hopTaskIdOf(re1);
        String k1r = receiptKeyOf(re1);
        assertThat(t1r).isNotEqualTo(t1);
        assertThat(k1r).isNotEqualTo(k1);
        // 设备按 device_id 升序返回，d1 为第一台
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(jsonPath("$.devices[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.devices[0].hops[0].tasks.length()").value(2))
                .andExpect(jsonPath("$.devices[0].hops[0].tasks[1].roundNo").value(2))
                .andExpect(jsonPath("$.devices[0].hops[0].tasks[1].status").value("PENDING"));

        // 新轮次成功推进：d1 完成该跳，d3 首次派发
        receipt(t1r, "r-rc1r", k1r, "SUCCESS");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        long t3 = hopTaskIdOf(dispatch(planId, "r-disp3r", "d3"));
        receipt(t3, "r-rc3r", receiptKeyOf(dispatch(planId, "r-disp3rb", "d3")), "SUCCESS");
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(jsonPath("$.plan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan.roundSuccess").value(2))
                .andExpect(jsonPath("$.rounds.length()").value(2))
                .andExpect(jsonPath("$.rounds[0].roundNo").value(1))
                .andExpect(jsonPath("$.rounds[0].failed").value(2))
                .andExpect(jsonPath("$.rounds[1].roundNo").value(2))
                .andExpect(jsonPath("$.rounds[1].success").value(2));

        // d2 仍未成功：计划未完结；d2 在新轮次重派并成功后才 COMPLETED
        long t2r = hopTaskIdOf(dispatch(planId, "r-disp2r", "d2"));
        receipt(t2r, "r-rc2r", receiptKeyOf(dispatch(planId, "r-disp2rb", "d2")), "SUCCESS");
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(jsonPath("$.plan.status").value("COMPLETED"));
    }

    @Test
    void 设备冲突_占用期间不得进入新投放_派发时校验未终结投放任务() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0");
        registerDevice("r-d2", "d2", "m1", "1.0.0");
        long rel = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");
        long rel2 = completeRollout("k2", "d2", "m1", "1.0.0", "2.0.0");
        long planId = createPlan("r-p", "pk-1", rel, "1.0.0", "[\"d1\"]");

        // d1 被回退计划占用：新投放拉取不创建任务
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-rel2","model":"m1","fromVersion":"2.0.0","toVersion":"3.0.0","ratio":100}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        // 未占用设备正常投放
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // d1 已在未终结回退计划中：第二个回退计划 409
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-p2","planKey":"pk-2","sourceReleaseId":%d,"targetVersion":"1.0.0",
                "deviceIds":["d1"]}
                """.formatted(rel)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_BUSY"));

        // d2 有未终结投放任务：纳入新回退计划 409
        mockMvc.perform(post("/api/rollback-plans").contentType("application/json").content("""
                {"requestId":"r-p3","planKey":"pk-3","sourceReleaseId":%d,"targetVersion":"1.0.0",
                "deviceIds":["d2"]}
                """.formatted(rel2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_BUSY"));

        // d1 取消回退计划后占用释放，可进入新投放
        mockMvc.perform(post("/api/rollback-plans/" + planId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));
    }

    @Test
    void 查询只读_明细不触发状态变化() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0");
        long rel = completeRollout("k1", "d1", "m1", "1.0.0", "2.0.0");
        long planId = createPlan("r-p", "pk-1", rel, "1.0.0", "[\"d1\"]");
        dispatch(planId, "r-disp", "d1");

        mockMvc.perform(get("/api/rollback-plans/" + planId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/rollback-plans/" + planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.plan.monitorRound").value(1))
                .andExpect(jsonPath("$.plan.roundSuccess").value(0))
                .andExpect(jsonPath("$.plan.roundFailed").value(0))
                .andExpect(jsonPath("$.devices[0].hops[0].tasks.length()").value(1))
                .andExpect(jsonPath("$.devices[0].hops[0].tasks[0].status").value("PENDING"));
        // 只读查询不产生新任务
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_hop_task", Long.class))
                .isEqualTo(1);
    }
}
