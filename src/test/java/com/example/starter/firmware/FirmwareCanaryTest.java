package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 金丝雀分级验证门禁 API 测试：逐级推进、失败拦截、跳级拒绝、promoteKey 幂等（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareCanaryTest {

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
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private void receipt(long taskId, String requestId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"%s\"}".formatted(requestId, result)))
                .andExpect(status().isOk());
    }

    private MvcResult promote(long releaseId, String promoteKey, int targetLevel) throws Exception {
        return mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"%s\",\"targetLevel\":%d}".formatted(promoteKey, targetLevel)))
                .andReturn();
    }

    @Test
    void 主流程_逐级解锁_最高级别推进后完成() throws Exception {
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        registerDevice("rd2", "d2", "m1", "1.0.0", 2);
        registerDevice("rd3", "d3", "m1", "1.0.0", 10);
        registerDevice("rd4", "d4", "m1", "1.0.0", 50);
        long releaseId = createCanaryRelease("rc1", "m1", """
                [{"ratio":5,"minSamples":2,"maxFailureRate":50},
                 {"ratio":20,"minSamples":1,"maxFailureRate":100},
                 {"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);

        // 初始仅解锁第1级，生效比例为第1级比例
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.levelCount").value(3))
                .andExpect(jsonPath("$.currentLevel").value(1))
                .andExpect(jsonPath("$.effectiveRatio").value(5))
                .andExpect(jsonPath("$.levels.length()").value(3))
                .andExpect(jsonPath("$.levels[0].unlocked").value(true))
                .andExpect(jsonPath("$.levels[0].samples").value(0))
                .andExpect(jsonPath("$.levels[0].failureRate").value(nullValue()))
                .andExpect(jsonPath("$.levels[1].unlocked").value(false))
                .andExpect(jsonPath("$.levels[2].unlocked").value(false))
                .andExpect(jsonPath("$.promotions.length()").value(0));

        // 未解锁级别区间的设备不得拉取任务
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-d3a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 第1级积累 2 个成功样本
        receipt(pullTask("d1", "rp-d1"), "rr-d1", "SUCCESS");
        receipt(pullTask("d2", "rp-d2"), "rr-d2", "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.levels[0].samples").value(2))
                .andExpect(jsonPath("$.levels[0].failures").value(0))
                .andExpect(jsonPath("$.levels[0].failureRate").value(0));

        // 推进到第2级：更多设备可按比例拉取
        promote(releaseId, "pk-1", 2);
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.currentLevel").value(2))
                .andExpect(jsonPath("$.effectiveRatio").value(20))
                .andExpect(jsonPath("$.levels[1].unlocked").value(true))
                .andExpect(jsonPath("$.promotions.length()").value(1))
                .andExpect(jsonPath("$.promotions[0].fromLevel").value(1))
                .andExpect(jsonPath("$.promotions[0].toLevel").value(2))
                .andExpect(jsonPath("$.promotions[0].promoteKey").value("pk-1"))
                .andExpect(jsonPath("$.promotions[0].samples").value(2));
        long task3 = pullTask("d3", "rp-d3b");
        receipt(task3, "rr-d3", "SUCCESS");

        // 推进到第3级（最高级）
        promote(releaseId, "pk-2", 3);
        receipt(pullTask("d4", "rp-d4"), "rr-d4", "SUCCESS");

        // 最高级别推进：进入 COMPLETED 终态
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-3\",\"targetLevel\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentLevel").value(3))
                .andExpect(jsonPath("$.promotions.length()").value(3))
                .andExpect(jsonPath("$.promotions[2].toLevel").value(4));

        // 完成后不再解锁更多设备
        registerDevice("rd5", "d5", "m1", "1.0.0", 1);
        mockMvc.perform(post("/api/devices/d5/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-d5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 完成后不能推进、不能手工扩量
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-4\",\"targetLevel\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_COMPLETED"));

        // 完成释放型号占用，可创建同型号新发布单
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rc2","model":"m1","fromVersion":"2.0.0","toVersion":"3.0.0","ratio":10}
                """))
                .andExpect(status().isOk());
    }

    @Test
    void 创建校验_级别数量_比例递增_参数边界() throws Exception {
        // 级别数量越界
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":1,"maxFailureRate":50},
                          {"ratio":10,"minSamples":1,"maxFailureRate":50},
                          {"ratio":20,"minSamples":1,"maxFailureRate":50},
                          {"ratio":40,"minSamples":1,"maxFailureRate":50},
                          {"ratio":60,"minSamples":1,"maxFailureRate":50},
                          {"ratio":80,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest());

        // 比例不递增（相等或下降）
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":1,"maxFailureRate":50},
                          {"ratio":5,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CANARY_LEVELS"));
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":50,"minSamples":1,"maxFailureRate":50},
                          {"ratio":20,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CANARY_LEVELS"));

        // 级别参数边界：比例、最小样本数、失败率上限
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c5","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":0,"minSamples":1,"maxFailureRate":50},
                          {"ratio":10,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c6","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":0,"maxFailureRate":50},
                          {"ratio":10,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c7","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":51,"maxFailureRate":50},
                          {"ratio":10,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c8","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                 "levels":[{"ratio":5,"minSamples":1,"maxFailureRate":101},
                          {"ratio":10,"minSamples":1,"maxFailureRate":50}]}
                """))
                .andExpect(status().isBadRequest());

        // 全部失败，未创建任何发布单
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"c9","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                """))
                .andExpect(status().isOk());
    }

    @Test
    void 样本不足_422说明差距_失败不占键补足后可推进() throws Exception {
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        registerDevice("rd2", "d2", "m1", "1.0.0", 2);
        registerDevice("rd3", "d3", "m1", "1.0.0", 3);
        long releaseId = createCanaryRelease("rc1", "m1", """
                [{"ratio":5,"minSamples":3,"maxFailureRate":50},
                 {"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        receipt(pullTask("d1", "rp-d1"), "rr-d1", "SUCCESS");
        receipt(pullTask("d2", "rp-d2"), "rr-d2", "SUCCESS");

        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAMPLE_INSUFFICIENT"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("还差 1")));

        // 失败不占键：补足样本后同一 promoteKey 可成功推进
        receipt(pullTask("d3", "rp-d3"), "rr-d3", "SUCCESS");
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value(2));
    }

    @Test
    void 失败率超限_422不能推进跳过_发布单仍可取消() throws Exception {
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        registerDevice("rd2", "d2", "m1", "1.0.0", 2);
        long releaseId = createCanaryRelease("rc1", "m1", """
                [{"ratio":50,"minSamples":2,"maxFailureRate":50},
                 {"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        receipt(pullTask("d1", "rp-d1"), "rr-d1", "FAILED");
        receipt(pullTask("d2", "rp-d2"), "rr-d2", "FAILED");

        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FAILURE_RATE_EXCEEDED"));

        // 推进被拦截，级别不变
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.currentLevel").value(1))
                .andExpect(jsonPath("$.levels[0].samples").value(2))
                .andExpect(jsonPath("$.levels[0].failures").value(2))
                .andExpect(jsonPath("$.levels[0].failureRate").value(100))
                .andExpect(jsonPath("$.promotions.length()").value(0));

        // 发布单仍可由既有取消机制处理；取消后不能推进
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rx-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-2\",\"targetLevel\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
    }

    @Test
    void 跳级与重复目标级别_422() throws Exception {
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        long releaseId = createCanaryRelease("rc1", "m1", """
                [{"ratio":5,"minSamples":1,"maxFailureRate":100},
                 {"ratio":20,"minSamples":1,"maxFailureRate":100},
                 {"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        receipt(pullTask("d1", "rp-d1"), "rr-d1", "SUCCESS");

        // 跳级：1 -> 3
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROMOTE_SKIP_LEVEL"));
        // 超出级别总数+1
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-2\",\"targetLevel\":5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROMOTE_SKIP_LEVEL"));
        // 目标级别小于2：参数校验 400
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-3\",\"targetLevel\":1}"))
                .andExpect(status().isBadRequest());

        // 正常推进到第2级后，重复推进第2级（新 promoteKey）视为跳级/回退：422
        promote(releaseId, "pk-4", 2);
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-5\",\"targetLevel\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROMOTE_SKIP_LEVEL"));
    }

    @Test
    void promoteKey幂等_同键同参重放_异参409() throws Exception {
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        long releaseId = createCanaryRelease("rc1", "m1", """
                [{"ratio":5,"minSamples":1,"maxFailureRate":100},
                 {"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        receipt(pullTask("d1", "rp-d1"), "rr-d1", "SUCCESS");

        MvcResult first = mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value(2))
                .andReturn();

        // 同键同参重放首次结果，推进不重复发生
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLevel").value(2))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.currentLevel").value(2))
                .andExpect(jsonPath("$.promotions.length()").value(1));

        // 同键异参：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 非金丝雀发布单_推进409_状态查询404() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rc1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                """))
                .andExpect(status().isOk())
                .andReturn();
        long releaseId = idOf(created, "$.releaseId");

        mockMvc.perform(post("/api/releases/" + releaseId + "/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-1\",\"targetLevel\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_CANARY"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CANARY_NOT_CONFIGURED"));
        mockMvc.perform(get("/api/releases/9999/canary"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_FOUND"));
        mockMvc.perform(post("/api/releases/9999/promote")
                        .contentType("application/json")
                        .content("{\"promoteKey\":\"pk-2\",\"targetLevel\":2}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 已解锁级别样本与历史不被推进重置() throws Exception {
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        registerDevice("rd2", "d2", "m1", "1.0.0", 2);
        registerDevice("rd3", "d3", "m1", "1.0.0", 10);
        long releaseId = createCanaryRelease("rc1", "m1", """
                [{"ratio":5,"minSamples":2,"maxFailureRate":100},
                 {"ratio":20,"minSamples":1,"maxFailureRate":100},
                 {"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        receipt(pullTask("d1", "rp-d1"), "rr-d1", "SUCCESS");
        receipt(pullTask("d2", "rp-d2"), "rr-d2", "FAILED");
        promote(releaseId, "pk-1", 2);

        // 第1级样本保留，推进历史快照保留
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.levels[0].samples").value(2))
                .andExpect(jsonPath("$.levels[0].failures").value(1))
                .andExpect(jsonPath("$.levels[0].failureRate").value(50))
                .andExpect(jsonPath("$.promotions[0].samples").value(2))
                .andExpect(jsonPath("$.promotions[0].failures").value(1));

        // 推进前已下发但未完成的任务不受影响，回执计入当前解锁级别（第2级）
        receipt(pullTask("d3", "rp-d3"), "rr-d3", "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/canary"))
                .andExpect(jsonPath("$.levels[0].samples").value(2))
                .andExpect(jsonPath("$.levels[1].samples").value(1))
                .andExpect(jsonPath("$.levels[1].failures").value(0));
    }

    @Test
    void 金丝雀发布单不能手工扩量() throws Exception {
        long releaseId = createCanaryRelease("rc1", "m1", """
                [{"ratio":5,"minSamples":1,"maxFailureRate":100},
                 {"ratio":100,"minSamples":1,"maxFailureRate":100}]
                """);
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json")
                        .content("{\"requestId\":\"re-1\",\"expectedVersion\":1,\"ratio\":50}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANARY_RELEASE_NO_EXPAND"));
    }
}
