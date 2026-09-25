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
 * 金丝雀分级验证门禁测试：创建校验、分级拉取门禁、推进门禁、失败自动暂停、
 * 跳级拒绝、promoteKey 幂等与只读统计查询（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CanaryGateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM canary_promotion");
        jdbc.update("DELETE FROM canary_level");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createCanaryRelease(String requestId, String model, String levelsJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","levels":%s}
                """.formatted(requestId, model, levelsJson)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullTask(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private void pullExpectNoTask(String deviceId, String requestId) throws Exception {
        mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
    }

    private void receipt(long taskId, String requestId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"" + requestId + "\",\"result\":\"" + result + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 创建_级别数量与取值校验_比例必须递增() throws Exception {
        // 仅 1 级：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":100,"minSamples":1,"maxFailureRate":50}]}
                """)).andExpect(status().isBadRequest());
        // 6 级：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":1,"maxFailureRate":50},{"ratio":10,"minSamples":1,"maxFailureRate":50},
                 {"ratio":20,"minSamples":1,"maxFailureRate":50},{"ratio":40,"minSamples":1,"maxFailureRate":50},
                 {"ratio":60,"minSamples":1,"maxFailureRate":50},{"ratio":100,"minSamples":1,"maxFailureRate":50}]}
                """)).andExpect(status().isBadRequest());
        // 比例非递增：400 LEVELS_NOT_INCREASING
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":20,"minSamples":1,"maxFailureRate":50},{"ratio":20,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEVELS_NOT_INCREASING"));
        // minSamples 越界：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":0,"maxFailureRate":50},{"ratio":100,"minSamples":1,"maxFailureRate":50}]}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v5","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":51,"maxFailureRate":50},{"ratio":100,"minSamples":1,"maxFailureRate":50}]}
                """)).andExpect(status().isBadRequest());
        // maxFailureRate 越界：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v6","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":1,"maxFailureRate":0},{"ratio":100,"minSamples":1,"maxFailureRate":50}]}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v7","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":1,"maxFailureRate":101},{"ratio":100,"minSamples":1,"maxFailureRate":50}]}
                """)).andExpect(status().isBadRequest());
        // 顶层 ratio 与第 1 级比例不一致：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v8","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                 "levels":[{"ratio":5,"minSamples":1,"maxFailureRate":50},{"ratio":100,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RATIO_LEVEL_MISMATCH"));
        // 普通灰度缺 ratio：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v9","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RATIO_REQUIRED"));
        // 合法分级创建：初始只解锁第 1 级，生效比例为第 1 级比例
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"v10","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":2,"maxFailureRate":50},{"ratio":100,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentLevel").value(1))
                .andExpect(jsonPath("$.ratio").value(5));
    }

    @Test
    void 分级门禁_未解锁级别不得拉取_样本达标后推进解锁() throws Exception {
        long releaseId = createCanaryRelease("c1", "m1", """
                [{"ratio":5,"minSamples":2,"maxFailureRate":50},
                 {"ratio":20,"minSamples":1,"maxFailureRate":50},
                 {"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        registerDevice("d-r1", "d1", "m1", "1.0.0", 3);
        registerDevice("d-r2", "d2", "m1", "1.0.0", 10);
        registerDevice("d-r3", "d3", "m1", "1.0.0", 60);
        registerDevice("d-r4", "d4", "m1", "1.0.0", 4);

        // 未解锁级别的设备不得拉取
        pullExpectNoTask("d2", "p-d2");
        pullExpectNoTask("d3", "p-d3");

        // 第 1 级样本未达标：推进 422 并说明差距
        long task1 = pullTask("d1", "p-d1");
        receipt(task1, "rc-1", "SUCCESS");
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GATE_SAMPLES_INSUFFICIENT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("还差 1")));

        // 第 2 个样本达标后推进成功，解锁第 2 级
        long task4 = pullTask("d4", "p-d4");
        receipt(task4, "rc-4", "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value(1))
                .andExpect(jsonPath("$.maxLevel").value(3))
                .andExpect(jsonPath("$.levels[0].sampleCount").value(2))
                .andExpect(jsonPath("$.levels[0].failedCount").value(0))
                .andExpect(jsonPath("$.levels[0].unlocked").value(true))
                .andExpect(jsonPath("$.levels[1].unlocked").value(false))
                .andExpect(jsonPath("$.promotions.length()").value(0));

        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("UNLOCKED"))
                .andExpect(jsonPath("$.currentLevel").value(2))
                .andExpect(jsonPath("$.ratio").value(20))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 第 2 级设备现在可以拉取；第 3 级仍锁定
        pullTask("d2", "p-d2b");
        pullExpectNoTask("d3", "p-d3b");

        // 已解锁级别样本与历史不因推进被重置
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.currentLevel").value(2))
                .andExpect(jsonPath("$.levels[0].sampleCount").value(2))
                .andExpect(jsonPath("$.levels[1].unlocked").value(true))
                .andExpect(jsonPath("$.promotions.length()").value(1))
                .andExpect(jsonPath("$.promotions[0].fromLevel").value(1))
                .andExpect(jsonPath("$.promotions[0].toLevel").value(2))
                .andExpect(jsonPath("$.promotions[0].action").value("UNLOCK"))
                .andExpect(jsonPath("$.promotions[0].sampleCount").value(2))
                .andExpect(jsonPath("$.promotions[0].promoteKey").value("pk-2"));
    }

    @Test
    void 推进_不能跳级_目标级别必须紧邻下一级() throws Exception {
        long releaseId = createCanaryRelease("c1", "m1", """
                [{"ratio":5,"minSamples":1,"maxFailureRate":50},
                 {"ratio":20,"minSamples":1,"maxFailureRate":50},
                 {"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        // 跳级：422
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LEVEL_SKIP"));
        // 目标级别不大于当前级别：422
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-2\",\"targetLevel\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LEVEL_INVALID"));
        // 紧邻下一级但样本不足：422 门禁差距
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-3\",\"targetLevel\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GATE_SAMPLES_INSUFFICIENT"));
    }

    @Test
    void 失败率超限_自动暂停_不能推进跳过_暂停后可取消() throws Exception {
        long releaseId = createCanaryRelease("c1", "m1", """
                [{"ratio":10,"minSamples":2,"maxFailureRate":50},{"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        registerDevice("d-r1", "d1", "m1", "1.0.0", 1);
        registerDevice("d-r2", "d2", "m1", "1.0.0", 2);
        registerDevice("d-r3", "d3", "m1", "1.0.0", 3);

        long task1 = pullTask("d1", "p-d1");
        long task2 = pullTask("d2", "p-d2");
        receipt(task1, "rc-1", "FAILED");

        // 失败率 100% 超过上限 50%：发布单自动暂停
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.levels[0].sampleCount").value(1))
                .andExpect(jsonPath("$.levels[0].failedCount").value(1))
                .andExpect(jsonPath("$.levels[0].failureRatePercent").value(100.0));

        // 暂停后新设备不得拉取
        pullExpectNoTask("d3", "p-d3");

        // 暂停的发布单不能被推进跳过
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELEASE_PAUSED"));

        // 已下发未完成任务不受影响：回执仍受理，但不计入样本（已暂停）
        mockMvc.perform(post("/api/tasks/" + task2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.levels[0].sampleCount").value(1));

        // 暂停可经既有取消机制处理
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"x-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND status = 'PENDING'",
                Long.class, releaseId)).isZero();
    }

    @Test
    void 失败率未超上限_可正常推进() throws Exception {
        long releaseId = createCanaryRelease("c1", "m1", """
                [{"ratio":50,"minSamples":2,"maxFailureRate":50},{"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        registerDevice("d-r1", "d1", "m1", "1.0.0", 1);
        registerDevice("d-r2", "d2", "m1", "1.0.0", 2);
        receipt(pullTask("d1", "p-d1"), "rc-1", "SUCCESS");
        receipt(pullTask("d2", "p-d2"), "rc-2", "FAILED");

        // 失败率 50%，等于上限未超过：不暂停，可推进
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.levels[0].failureRatePercent").value(50.0));
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("UNLOCKED"))
                .andExpect(jsonPath("$.currentLevel").value(2));
    }

    @Test
    void 推进幂等_同键同参重放_异参409_失败不占键() throws Exception {
        long releaseId = createCanaryRelease("c1", "m1", """
                [{"ratio":5,"minSamples":2,"maxFailureRate":50},{"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        registerDevice("d-r1", "d1", "m1", "1.0.0", 1);
        registerDevice("d-r2", "d2", "m1", "1.0.0", 2);
        registerDevice("d-r3", "d3", "m1", "1.0.0", 3);
        receipt(pullTask("d1", "p-d1"), "rc-1", "SUCCESS");
        receipt(pullTask("d2", "p-d2"), "rc-2", "SUCCESS");

        MvcResult first = mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json").content("{\"promoteKey\":\"pk-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value(2))
                .andReturn();
        // 同键同参重放首次结果，不重复推进
        MvcResult replay = mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json").content("{\"promoteKey\":\"pk-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value(2))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM canary_promotion", Long.class)).isEqualTo(1);
        // 同键异参：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：第 2 级样本不足时推进 422，补齐样本后同键成功
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GATE_SAMPLES_INSUFFICIENT"));
        receipt(pullTask("d3", "p-d3"), "rc-3", "SUCCESS");
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("COMPLETED"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void 最高级别推进_进入完成终态_不再解锁设备_已下发任务不受影响() throws Exception {
        long releaseId = createCanaryRelease("c1", "m1", """
                [{"ratio":50,"minSamples":1,"maxFailureRate":100},{"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        registerDevice("d-r1", "d1", "m1", "1.0.0", 10);
        registerDevice("d-r2", "d2", "m1", "1.0.0", 70);
        registerDevice("d-r3", "d3", "m1", "1.0.0", 80);
        registerDevice("d-r4", "d4", "m1", "1.0.0", 90);

        receipt(pullTask("d1", "p-d1"), "rc-1", "SUCCESS");
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("UNLOCKED"))
                .andExpect(jsonPath("$.ratio").value(100));

        // 推进前已下发但未完成的任务
        long pendingTask = pullTask("d2", "p-d2");
        receipt(pullTask("d3", "p-d3"), "rc-3", "SUCCESS");

        // 最高级别推进：进入 COMPLETED 终态
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("COMPLETED"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // 终态后不再解锁更多设备
        pullExpectNoTask("d4", "p-d4");
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_PROMOTABLE"));

        // 推进前已下发但未完成的任务不受影响：回执仍受理并更新设备版本
        mockMvc.perform(post("/api/tasks/" + pendingTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc-2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 推进历史完整，终态记录 toLevel 为空
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.promotions.length()").value(2))
                .andExpect(jsonPath("$.promotions[1].action").value("COMPLETE"))
                .andExpect(jsonPath("$.promotions[1].toLevel").doesNotExist());

        // 终态释放型号占用，可创建新发布单
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c2","model":"m1","fromVersion":"2.0.0","toVersion":"3.0.0","ratio":10}
                """))
                .andExpect(status().isOk());
    }

    @Test
    void 边界_普通发布单不能推进_分级发布单不能手工扩量_查询不存在404() throws Exception {
        // 普通发布单无分级：推进 422
        MvcResult plain = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                """)).andExpect(status().isOk()).andReturn();
        long plainId = idOf(plain, "$.releaseId");
        mockMvc.perform(post("/api/releases/" + plainId + "/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_CANARY_LEVELS"));

        // 分级发布单不能手工扩量
        long canaryId = createCanaryRelease("c2", "m2", """
                [{"ratio":5,"minSamples":1,"maxFailureRate":50},{"ratio":100,"minSamples":1,"maxFailureRate":50}]
                """);
        mockMvc.perform(post("/api/releases/" + canaryId + "/expand").contentType("application/json")
                        .content("{\"requestId\":\"e-1\",\"expectedVersion\":1,\"ratio\":50}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANARY_MANAGED"));

        // 查询不存在的发布单：404
        mockMvc.perform(get("/api/releases/9999/canary"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/releases/9999/promote").contentType("application/json")
                        .content("{\"promoteKey\":\"pk-9\"}"))
                .andExpect(status().isNotFound());
    }
}
