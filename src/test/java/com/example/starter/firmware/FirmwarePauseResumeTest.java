package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 失败率自动暂停与人工恢复 API 测试（H2 内存库，MODE=MySQL）：
 * 阈值边界、重复回执不重复计数、PAUSED 行为、恢复新轮次、取消/恢复顺序、幂等与只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwarePauseResumeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM incompatible_record");
        jdbc.update("DELETE FROM firmware_compat");
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

    private void registerDevice(String requestId, String deviceId, String model, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, model, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio, int sampleFloor, int threshold)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,
                "sampleFloor":%d,"failureThresholdPercent":%d}
                """.formatted(requestId, model, ratio, sampleFloor, threshold)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
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

    @Test
    void 阈值边界_达到阈值当次回执同事务原子暂停并落暂停记录() throws Exception {
        // floor=3, threshold=50：失败数×100 >= 样本数×50 时暂停
        long releaseId = createRelease("r-create", "m1", 100, 3, 50);
        for (int i = 1; i <= 4; i++) {
            registerDevice("r-dev" + i, "d" + i, "m1", i);
        }
        long t1 = pullTask("r-pull1", "d1");
        long t2 = pullTask("r-pull2", "d2");
        long t3 = pullTask("r-pull3", "d3");
        long t4 = pullTask("r-pull4", "d4");

        // 样本 1：未达下限
        receipt(t1, "r-rc1", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monitorRound").value(1))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 样本 2：未达下限
        receipt(t2, "r-rc2", "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(1));

        // 样本 3：失败率 1/3 < 50%，不暂停
        receipt(t3, "r-rc3", "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(2));

        // 样本 4：失败率 2/4 = 50%，恰好等于阈值，当次回执事务内原子暂停
        receipt(t4, "r-rc4", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.monitorRound").value(1))
                .andExpect(jsonPath("$.roundFailed").value(2))
                .andExpect(jsonPath("$.roundSuccess").value(2))
                .andExpect(jsonPath("$.sampleFloor").value(3))
                .andExpect(jsonPath("$.failureThresholdPercent").value(50));

        // 暂停记录：轮次、触发任务、成功/失败数、UTC 时刻
        mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(jsonPath("$.pauses.length()").value(1))
                .andExpect(jsonPath("$.pauses[0].monitorRound").value(1))
                .andExpect(jsonPath("$.pauses[0].triggerTaskId").value(t4))
                .andExpect(jsonPath("$.pauses[0].successCount").value(2))
                .andExpect(jsonPath("$.pauses[0].failedCount").value(2))
                .andExpect(jsonPath("$.pauses[0].pausedAtUtc").isString())
                .andExpect(jsonPath("$.resumes.length()").value(0));
        String pausedAt = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(get("/api/releases/" + releaseId + "/history")).andReturn()
                        .getResponse().getContentAsString(), "$.pauses[0].pausedAtUtc");
        org.assertj.core.api.Assertions.assertThat(pausedAt).endsWith("Z");
    }

    @Test
    void 重复回执不重复计数_取消任务不计样本() throws Exception {
        // floor=2, threshold=100：仅当 2/2 全失败才暂停
        long releaseId = createRelease("r-create", "m1", 100, 2, 100);
        registerDevice("r-dev1", "d1", "m1", 1);
        registerDevice("r-dev2", "d2", "m1", 2);
        registerDevice("r-dev3", "d3", "m1", 3);
        long t1 = pullTask("r-pull1", "d1");
        long t2 = pullTask("r-pull2", "d2");
        long t3 = pullTask("r-pull3", "d3");

        receipt(t1, "r-rc1", "FAILED");
        // 同结果重复回执（新 requestId）：成功但不重复计数
        receipt(t1, "r-rc1-dup", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 取消发布单：PENDING 任务 t2/t3 转 CANCELLED，不计样本
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));
        // 已取消任务的后到回执 409，且不计样本
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(post("/api/tasks/" + t3 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc3\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));
    }

    @Test
    void PAUSED时不得扩量或新建任务_已有任务可回执并继续计数() throws Exception {
        long releaseId = createRelease("r-create", "m1", 100, 2, 50);
        registerDevice("r-dev1", "d1", "m1", 1);
        registerDevice("r-dev2", "d2", "m1", 2);
        registerDevice("r-dev3", "d3", "m1", 3);
        registerDevice("r-dev4", "d4", "m1", 4);
        long t1 = pullTask("r-pull1", "d1");
        long t2 = pullTask("r-pull2", "d2");
        long t3 = pullTask("r-pull3", "d3");
        receipt(t1, "r-rc1", "FAILED");
        receipt(t2, "r-rc2", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // PAUSED 不得扩量
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json")
                        .content("{\"requestId\":\"r-exp\",\"expectedVersion\":1,\"ratio\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));

        // PAUSED 不为新设备创建任务
        mockMvc.perform(post("/api/devices/d4/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 已有任务持有设备重复拉取仍返回原任务
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull3b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(t3))
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 已有任务仍可提交回执，且计入当前轮次统计
        receipt(t3, "r-rc3", "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.roundFailed").value(2))
                .andExpect(jsonPath("$.roundSuccess").value(1));

        // 暂停记录仍只有一条
        mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(jsonPath("$.pauses.length()").value(1));
    }

    @Test
    void 恢复_版本加一开启新轮次_统计清零_历史可查_不重开已终结任务() throws Exception {
        long releaseId = createRelease("r-create", "m1", 100, 2, 50);
        registerDevice("r-dev1", "d1", "m1", 1);
        registerDevice("r-dev2", "d2", "m1", 2);
        registerDevice("r-dev3", "d3", "m1", 3);
        long t1 = pullTask("r-pull1", "d1");
        long t2 = pullTask("r-pull2", "d2");
        receipt(t1, "r-rc1", "FAILED");
        receipt(t2, "r-rc2", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // expectedVersion 不匹配：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res0\",\"expectedVersion\":99,\"reason\":\"误报\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正确恢复：版本 1->2，轮次 1->2，统计清零
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res1\",\"expectedVersion\":1,\"reason\":\"已修复固件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.monitorRound").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 历史：1 条暂停（轮次1）+ 1 条恢复（新轮次2，含原因与 UTC 时刻）
        MvcResult history = mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(jsonPath("$.pauses.length()").value(1))
                .andExpect(jsonPath("$.pauses[0].monitorRound").value(1))
                .andExpect(jsonPath("$.resumes.length()").value(1))
                .andExpect(jsonPath("$.resumes[0].newRound").value(2))
                .andExpect(jsonPath("$.resumes[0].reason").value("已修复固件"))
                .andReturn();
        String resumedAt = com.jayway.jsonpath.JsonPath.read(
                history.getResponse().getContentAsString(), "$.resumes[0].resumedAtUtc");
        org.assertj.core.api.Assertions.assertThat(resumedAt).endsWith("Z");

        // 恢复不重开 FAILED 任务：d1 拉取仍返回原 FAILED 任务
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(t1))
                .andExpect(jsonPath("$.task.status").value("FAILED"));

        // 恢复后按原比例继续投放新设备
        long t3 = pullTask("r-pull3", "d3");
        org.assertj.core.api.Assertions.assertThat(t3).isPositive();

        // ACTIVE 状态不可恢复
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res2\",\"expectedVersion\":2,\"reason\":\"再次\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_PAUSED"));
    }

    @Test
    void 恢复幂等_同键同参重放版本只加一次_异参409() throws Exception {
        long releaseId = createRelease("r-create", "m1", 100, 2, 50);
        registerDevice("r-dev1", "d1", "m1", 1);
        registerDevice("r-dev2", "d2", "m1", 2);
        receipt(pullTask("r-pull1", "d1"), "r-rc1", "FAILED");
        receipt(pullTask("r-pull2", "d2"), "r-rc2", "FAILED");

        MvcResult first = mockMvc.perform(post("/api/releases/" + releaseId + "/resume")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r-res\",\"expectedVersion\":1,\"reason\":\"修复完成\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();
        // 同 requestId 同参重放：版本不再增加，响应一致
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res\",\"expectedVersion\":1,\"reason\":\"修复完成\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        // 同 requestId 异参：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res\",\"expectedVersion\":1,\"reason\":\"另一个原因\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 恢复记录只有一条
        mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(jsonPath("$.resumes.length()").value(1));
    }

    @Test
    void 取消先提交则恢复409_取消PAUSED继续取消未终结任务() throws Exception {
        long releaseId = createRelease("r-create", "m1", 100, 2, 50);
        registerDevice("r-dev1", "d1", "m1", 1);
        registerDevice("r-dev2", "d2", "m1", 2);
        registerDevice("r-dev3", "d3", "m1", 3);
        long t1 = pullTask("r-pull1", "d1");
        long t2 = pullTask("r-pull2", "d2");
        long t3 = pullTask("r-pull3", "d3");
        receipt(t1, "r-rc1", "FAILED");
        receipt(t2, "r-rc2", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // 取消 PAUSED 发布单：未终结任务继续取消
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "PENDING"))
                .andExpect(jsonPath("$.tasks.length()").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.tasks.length()").value(1));

        // 取消先提交：恢复 409
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res\",\"expectedVersion\":1,\"reason\":\"太晚\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_CANCELLED"));

        // CANCELLED 为终态：再次取消幂等返回 CANCELLED，恢复仍 409
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        org.assertj.core.api.Assertions.assertThat(t3).isPositive();
    }

    @Test
    void 恢复后回执计入新轮次_可再次暂停并各留一条记录() throws Exception {
        long releaseId = createRelease("r-create", "m1", 100, 2, 50);
        for (int i = 1; i <= 4; i++) {
            registerDevice("r-dev" + i, "d" + i, "m1", i);
        }
        long t1 = pullTask("r-pull1", "d1");
        long t2 = pullTask("r-pull2", "d2");
        long t3 = pullTask("r-pull3", "d3");
        long t4 = pullTask("r-pull4", "d4");
        receipt(t1, "r-rc1", "FAILED");
        receipt(t2, "r-rc2", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-res\",\"expectedVersion\":1,\"reason\":\"修复\"}"))
                .andExpect(status().isOk());

        // 恢复后提交的回执计入其完成时所在的新轮次（第2轮）
        receipt(t3, "r-rc3", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 新轮次再次达到阈值：再次暂停，历史各留一条
        receipt(t4, "r-rc4", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(jsonPath("$.roundFailed").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(jsonPath("$.pauses.length()").value(2))
                .andExpect(jsonPath("$.pauses[0].monitorRound").value(1))
                .andExpect(jsonPath("$.pauses[1].monitorRound").value(2))
                .andExpect(jsonPath("$.pauses[1].triggerTaskId").value(t4))
                .andExpect(jsonPath("$.resumes.length()").value(1));
    }

    @Test
    void 创建_监控参数校验与默认值_查询只读且发布单不存在404() throws Exception {
        // 参数越界：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-b1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,"sampleFloor":1}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-b2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,"sampleFloor":101}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-b3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,"failureThresholdPercent":0}
                """)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-b4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,"failureThresholdPercent":101}
                """)).andExpect(status().isBadRequest());

        // 旧客户端不传监控参数：默认值 floor=2 / threshold=100
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-ok","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleFloor").value(2))
                .andExpect(jsonPath("$.failureThresholdPercent").value(100))
                .andExpect(jsonPath("$.monitorRound").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        long releaseId = jdbc.queryForObject("SELECT id FROM release_order", Long.class);

        // 只读查询不触发状态变化：连续查询后版本/状态/统计不变
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor")).andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pauses.length()").value(0))
                .andExpect(jsonPath("$.resumes.length()").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monitorRound").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 发布单不存在：404
        mockMvc.perform(get("/api/releases/9999/monitor")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/history")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/releases/9999/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-404\",\"expectedVersion\":1,\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
