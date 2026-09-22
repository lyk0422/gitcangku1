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
 * 失败率自动暂停与人工恢复 API 测试（H2 内存库，MODE=MySQL）。
 * 覆盖阈值边界、重复回执不计数、PAUSED 行为、取消 PAUSED、恢复主流程与校验分支、
 * 恢复幂等、多轮历史与只读查询无副作用。
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
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio, int sampleFloor, int threshold)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,
                         "sampleFloor":%d,"failureThresholdPercent":%d}
                        """.formatted(requestId, model, ratio, sampleFloor, threshold)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monitorRound").value(1))
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private long pull(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.task.taskId");
        return id.longValue();
    }

    private void receipt(String requestId, long taskId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"%s\"}".formatted(requestId, result)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(result));
    }

    @Test
    void 阈值边界_样本不足不暂停_达到阈值等号即暂停() throws Exception {
        for (int i = 1; i <= 3; i++) {
            registerDevice("d" + i, "d" + i, "m1", "1.0.0", i);
        }
        long releaseId = createRelease("r1", "m1", 100, 2, 60);
        long t1 = pull("p1", "d1");
        long t2 = pull("p2", "d2");
        long t3 = pull("p3", "d3");

        // 样本数 1 < 下限 2：不暂停
        receipt("rc1", t1, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sampleCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1));

        // 样本数 2，失败率 1/2=50% < 60%：不暂停
        receipt("rc2", t2, "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.sampleCount").value(2));

        // 样本数 3，失败 2：2*100=200 >= 3*60=180，等号以上即暂停
        receipt("rc3", t3, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.monitorRound").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(2))
                .andExpect(jsonPath("$.sampleCount").value(3));

        // 暂停记录：轮次、触发任务、成功/失败数、UTC 时刻
        mockMvc.perform(get("/api/releases/" + releaseId + "/pause-records"))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].monitorRound").value(1))
                .andExpect(jsonPath("$.records[0].triggerTaskId").value(t3))
                .andExpect(jsonPath("$.records[0].successCount").value(1))
                .andExpect(jsonPath("$.records[0].failureCount").value(2))
                .andExpect(jsonPath("$.records[0].pausedAt").isNotEmpty());
    }

    @Test
    void 重复回执不重复计数_同结果重放不触发暂停() throws Exception {
        registerDevice("d1", "d1", "m1", "1.0.0", 1);
        registerDevice("d2", "d2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r1", "m1", 100, 2, 50);
        long t1 = pull("p1", "d1");
        long t2 = pull("p2", "d2");

        receipt("rc1", t1, "FAILED");
        // 同任务同结果重复回执（新 requestId）：成功但不重复计数
        receipt("rc2", t1, "FAILED");
        // 同 requestId 重放：返回原结果，亦不重复计数
        receipt("rc1", t1, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.sampleCount").value(1));

        // 第二台失败后样本达下限且失败率 100% >= 50%：暂停
        receipt("rc3", t2, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.failureCount").value(2))
                .andExpect(jsonPath("$.sampleCount").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/pause-records"))
                .andExpect(jsonPath("$.records.length()").value(1));
    }

    @Test
    void PAUSED_不扩量不建新任务_已有任务可回执且不再生成暂停记录() throws Exception {
        for (int i = 1; i <= 3; i++) {
            registerDevice("d" + i, "d" + i, "m1", "1.0.0", i);
        }
        long releaseId = createRelease("r1", "m1", 100, 2, 50);
        long t1 = pull("p1", "d1");
        long t2 = pull("p2", "d2");
        long t3 = pull("p3", "d3");
        receipt("rc1", t1, "FAILED");
        receipt("rc2", t2, "FAILED");

        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // 不得扩量
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json")
                        .content("{\"requestId\":\"e1\",\"expectedVersion\":1,\"ratio\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));

        // 不为新设备创建任务
        registerDevice("d4", "d4", "m1", "1.0.0", 4);
        mockMvc.perform(post("/api/devices/d4/pull").contentType("application/json")
                        .content("{\"requestId\":\"p4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 已有任务仍返回并可提交回执，回执仍计入当前轮次统计
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(t3));
        receipt("rc3", t3, "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(2))
                .andExpect(jsonPath("$.sampleCount").value(3));

        // 已 PAUSED 不再生成新的暂停记录
        mockMvc.perform(get("/api/releases/" + releaseId + "/pause-records"))
                .andExpect(jsonPath("$.records.length()").value(1));
    }

    @Test
    void 取消PAUSED_未终结任务转CANCELLED_恢复409() throws Exception {
        for (int i = 1; i <= 3; i++) {
            registerDevice("d" + i, "d" + i, "m1", "1.0.0", i);
        }
        long releaseId = createRelease("r1", "m1", 100, 2, 50);
        long t1 = pull("p1", "d1");
        long t2 = pull("p2", "d2");
        long t3 = pull("p3", "d3");
        receipt("rc1", t1, "FAILED");
        receipt("rc2", t2, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"x1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 未终结任务继续取消
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.tasks.length()").value(1));

        // 取消后恢复：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs1\",\"expectedVersion\":1,\"reason\":\"已修复\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_PAUSED"));

        // 已取消任务回执：409
        mockMvc.perform(post("/api/tasks/" + t3 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc9\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
    }

    @Test
    void 人工恢复_版本加一新轮统计归零_历史不可改_恢复后可继续投放() throws Exception {
        for (int i = 1; i <= 3; i++) {
            registerDevice("d" + i, "d" + i, "m1", "1.0.0", i);
        }
        long releaseId = createRelease("r1", "m1", 100, 2, 50);
        long t1 = pull("p1", "d1");
        long t2 = pull("p2", "d2");
        receipt("rc1", t1, "FAILED");
        receipt("rc2", t2, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // PAUSED 时新设备不投放
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(jsonPath("$.task").doesNotExist());

        // 恢复：版本 1->2，开启第 2 轮
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs1\",\"expectedVersion\":1,\"reason\":\"热修复已上线\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.monitorRound").value(2));

        // 新轮统计从零开始
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.sampleCount").value(0));

        // 恢复记录：新轮次、新版本、原因、UTC 时刻
        mockMvc.perform(get("/api/releases/" + releaseId + "/resume-records"))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].monitorRound").value(2))
                .andExpect(jsonPath("$.records[0].version").value(2))
                .andExpect(jsonPath("$.records[0].reason").value("热修复已上线"))
                .andExpect(jsonPath("$.records[0].resumedAt").isNotEmpty());

        // 历史暂停记录不可改
        mockMvc.perform(get("/api/releases/" + releaseId + "/pause-records"))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].monitorRound").value(1))
                .andExpect(jsonPath("$.records[0].failureCount").value(2));

        // 恢复后新设备可按原比例拉取
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 恢复不重开 FAILED 任务：d1 拉取仍返回原 FAILED 任务
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(t1))
                .andExpect(jsonPath("$.task.status").value("FAILED"));

        // 旧轮 FAILED 任务的重复回执不计入新轮统计
        receipt("rc1b", t1, "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.sampleCount").value(0));
    }

    @Test
    void 恢复校验_ACTIVE不可恢复_版本冲突_不存在404_缺原因400_失败不占键() throws Exception {
        registerDevice("d1", "d1", "m1", "1.0.0", 1);
        registerDevice("d2", "d2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r1", "m1", 100, 2, 50);

        // ACTIVE 状态恢复：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs0\",\"expectedVersion\":1,\"reason\":\"过早恢复\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_PAUSED"));

        // 不存在的发布单：404
        mockMvc.perform(post("/api/releases/9999/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs9\",\"expectedVersion\":1,\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());

        // 缺少原因：400
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs8\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());

        long t1 = pull("p1", "d1");
        long t2 = pull("p2", "d2");
        receipt("rc1", t1, "FAILED");
        receipt("rc2", t2, "FAILED");

        // 版本冲突：409，且失败不占键
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs1\",\"expectedVersion\":99,\"reason\":\"热修复\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs1\",\"expectedVersion\":1,\"reason\":\"热修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void 恢复幂等_同键同参重放_异参409_副作用只发生一次() throws Exception {
        registerDevice("d1", "d1", "m1", "1.0.0", 1);
        registerDevice("d2", "d2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r1", "m1", 100, 2, 50);
        long t1 = pull("p1", "d1");
        long t2 = pull("p2", "d2");
        receipt("rc1", t1, "FAILED");
        receipt("rc2", t2, "FAILED");

        MvcResult first = mockMvc.perform(post("/api/releases/" + releaseId + "/resume")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rs1\",\"expectedVersion\":1,\"reason\":\"热修复\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // 同键同参重放：响应一致，版本与轮次不再增加
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs1\",\"expectedVersion\":1,\"reason\":\"热修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));

        // 同键异参：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs1\",\"expectedVersion\":1,\"reason\":\"另一个原因\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 副作用只发生一次：恢复记录仅一条，发布单版本未被重复加一
        mockMvc.perform(get("/api/releases/" + releaseId + "/resume-records"))
                .andExpect(jsonPath("$.records.length()").value(1));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.monitorRound").value(2));
    }

    @Test
    void 恢复后再次暂停_多轮历史各自独立() throws Exception {
        for (int i = 1; i <= 4; i++) {
            registerDevice("d" + i, "d" + i, "m1", "1.0.0", i);
        }
        long releaseId = createRelease("r1", "m1", 100, 2, 50);

        // 第 1 轮：两失败暂停
        receipt("rc1", pull("p1", "d1"), "FAILED");
        receipt("rc2", pull("p2", "d2"), "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // 恢复进入第 2 轮
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"rs1\",\"expectedVersion\":1,\"reason\":\"热修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitorRound").value(2));

        // 第 2 轮：再次两失败暂停
        receipt("rc3", pull("p3", "d3"), "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.failureCount").value(1));
        receipt("rc4", pull("p4", "d4"), "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(jsonPath("$.failureCount").value(2));

        // 两轮各一条暂停记录，历史不可改
        mockMvc.perform(get("/api/releases/" + releaseId + "/pause-records"))
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[0].monitorRound").value(1))
                .andExpect(jsonPath("$.records[1].monitorRound").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/resume-records"))
                .andExpect(jsonPath("$.records.length()").value(1));
    }

    @Test
    void 创建校验与兼容_监控参数越界400_缺省保守默认_只读查询无副作用() throws Exception {
        // sampleFloor 越界：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"b1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "sampleFloor":1,"failureThresholdPercent":50}
                        """))
                .andExpect(status().isBadRequest());
        // failureThresholdPercent 越界：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"b2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "sampleFloor":2,"failureThresholdPercent":0}
                        """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"b3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "sampleFloor":2,"failureThresholdPercent":101}
                        """))
                .andExpect(status().isBadRequest());

        // 旧客户端不传监控字段：兼容创建，应用保守默认值
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleFloor").value(100))
                .andExpect(jsonPath("$.failureThresholdPercent").value(100))
                .andExpect(jsonPath("$.monitorRound").value(1))
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.releaseId");
        long releaseId = id.longValue();

        // 默认配置下两次失败不触发暂停（样本下限 100）
        registerDevice("d1", "d1", "m1", "1.0.0", 1);
        registerDevice("d2", "d2", "m1", "1.0.0", 2);
        receipt("rc1", pull("p1", "d1"), "FAILED");
        receipt("rc2", pull("p2", "d2"), "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.failureCount").value(2));

        // 只读查询不触发状态变化：反复查询后状态与历史不变
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
            mockMvc.perform(get("/api/releases/" + releaseId + "/pause-records"))
                    .andExpect(jsonPath("$.records.length()").value(0));
            mockMvc.perform(get("/api/releases/" + releaseId + "/resume-records"))
                    .andExpect(jsonPath("$.records.length()").value(0));
        }

        // 只读查询不存在的发布单：404
        mockMvc.perform(get("/api/releases/9999/monitor"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/pause-records"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/resume-records"))
                .andExpect(status().isNotFound());
    }
}
