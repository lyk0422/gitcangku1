package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备隔离主流程、失败分支与幂等边界 API 测试（真实 H2 内存库，MODE=MySQL）：
 * 同设备同版本跨发布单连续两次失败 -> QUARANTINED -> 拉取 409 -> 异人解除 -> 新一轮投放，
 * 覆盖隔离依据固化、解除审核人约束、版本独立计数、历史保留与回执/解除幂等。
 * 时钟使用可控 MutableClock，精确核对隔离与解除时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FirmwareQuarantineApiTest.MutableClockConfig.class)
class FirmwareQuarantineApiTest {

    /**
     * 可控时钟：测试内可随时拨到指定 UTC 时刻。
     */
    static class MutableClock extends Clock {
        volatile Instant instant = Instant.parse("2026-09-30T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM device_failure_round");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.instant = Instant.parse("2026-09-30T08:00:00Z");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String deviceId) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"reg-%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(deviceId, deviceId))).andExpect(status().isOk());
    }

    private long createRelease(String requestId, String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"%s","toVersion":"%s","ratio":100}
                """.formatted(requestId, from, to))).andExpect(status().isOk()).andReturn();
        return idOf(result, "$.releaseId");
    }

    private void cancelRelease(long releaseId, String requestId) throws Exception {
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"%s\"}".formatted(requestId))).andExpect(status().isOk());
    }

    private long pull(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private void receiptFailed(long taskId, String requestId, String reason, String operator) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                {"requestId":"%s","result":"FAILED","failureReason":"%s","operatorId":"%s"}
                """.formatted(requestId, reason, operator))).andExpect(status().isOk());
    }

    @Test
    void 同版本连续两次失败后隔离_依据固化_拉取409_查询完整() throws Exception {
        registerDevice("d1");
        long r1 = createRelease("c1", "1.0.0", "2.0.0");
        long t1 = pull("d1", "p1");
        receiptFailed(t1, "f1", "烧录失败A", "op-a");

        // 第一次失败：ACTIVE 轮次，计数1，第二次任务/确认人/隔离时刻均为空
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureRounds.length()").value(1))
                .andExpect(jsonPath("$.failureRounds[0].toVersion").value("2.0.0"))
                .andExpect(jsonPath("$.failureRounds[0].roundNo").value(1))
                .andExpect(jsonPath("$.failureRounds[0].consecutiveFailed").value(1))
                .andExpect(jsonPath("$.failureRounds[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.failureRounds[0].firstTaskId").value(t1))
                .andExpect(jsonPath("$.failureRounds[0].firstFailureReason").value("烧录失败A"))
                .andExpect(jsonPath("$.failureRounds[0].secondTaskId").doesNotExist())
                .andExpect(jsonPath("$.failureRounds[0].confirmedBy").doesNotExist())
                .andExpect(jsonPath("$.failureRounds[0].quarantinedAtUtc").doesNotExist());

        // 一次失败不拦截投放：取消 R1 后同版本 R2 仍可领取
        cancelRelease(r1, "x1");
        long r2 = createRelease("c2", "1.0.0", "2.0.0");
        long t2 = pull("d1", "p2");
        assertThat(t2).isNotEqualTo(t1);

        // 第二次失败：同事务固化两次任务、版本、失败原因与确认人并隔离
        receiptFailed(t2, "f2", "校验失败B", "op-b");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.failureRounds.length()").value(1))
                .andExpect(jsonPath("$.failureRounds[0].status").value("QUARANTINED"))
                .andExpect(jsonPath("$.failureRounds[0].consecutiveFailed").value(2))
                .andExpect(jsonPath("$.failureRounds[0].roundNo").value(1))
                .andExpect(jsonPath("$.failureRounds[0].firstTaskId").value(t1))
                .andExpect(jsonPath("$.failureRounds[0].secondTaskId").value(t2))
                .andExpect(jsonPath("$.failureRounds[0].firstFailureReason").value("烧录失败A"))
                .andExpect(jsonPath("$.failureRounds[0].secondFailureReason").value("校验失败B"))
                .andExpect(jsonPath("$.failureRounds[0].confirmedBy").value("op-b"))
                .andExpect(jsonPath("$.failureRounds[0].quarantinedAtUtc").value("2026-09-30T08:00:00Z"))
                .andExpect(jsonPath("$.failureRounds[0].releasedBy").doesNotExist());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM device_failure_round WHERE device_id = 'd1'", String.class))
                .isEqualTo("QUARANTINED");

        // 隔离期间拉取一律 409（即使 R2 上已有失败任务也不返回）
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));

        // 旧两次失败任务仍可按发布单查询
        mockMvc.perform(get("/api/releases/" + r1 + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].taskId").value(t1))
                .andExpect(jsonPath("$.tasks[0].status").value("FAILED"));
        mockMvc.perform(get("/api/releases/" + r2 + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].taskId").value(t2))
                .andExpect(jsonPath("$.tasks[0].status").value("FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE device_id = 'd1'",
                Long.class)).isEqualTo(2);
    }

    @Test
    void 解除_审核人必须不同_成功后开启新一轮_历史失败保留且计数独立() throws Exception {
        registerDevice("d1");
        long r1 = createRelease("c1", "1.0.0", "2.0.0");
        long t1 = pull("d1", "p1");
        receiptFailed(t1, "f1", "烧录失败A", "op-a");
        cancelRelease(r1, "x1");
        long r2 = createRelease("c2", "1.0.0", "2.0.0");
        long t2 = pull("d1", "p2");
        receiptFailed(t2, "f2", "校验失败B", "op-b");

        // 与确认人相同：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content("""
                {"requestId":"q1","toVersion":"2.0.0","operatorId":"op-b","reason":"自己解除"}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_OPERATOR"));
        // 缺少必填字段：400
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content("""
                {"requestId":"q2","toVersion":"2.0.0","operatorId":"op-c"}
                """)).andExpect(status().isBadRequest());

        // 不同操作人解除成功，记录解除人与时刻
        clock.instant = Instant.parse("2026-09-30T10:30:00Z");
        MvcResult released = mockMvc.perform(post("/api/devices/d1/quarantine/release")
                        .contentType("application/json").content("""
                                {"requestId":"q3","toVersion":"2.0.0","operatorId":"op-c","reason":"现场复核通过"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.consecutiveFailed").value(2))
                .andExpect(jsonPath("$.releasedBy").value("op-c"))
                .andExpect(jsonPath("$.releaseReason").value("现场复核通过"))
                .andExpect(jsonPath("$.releasedAtUtc").value("2026-09-30T10:30:00Z"))
                .andReturn();

        // 同 requestId 同参重放：响应一致，不重复解除
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content("""
                {"requestId":"q3","toVersion":"2.0.0","operatorId":"op-c","reason":"现场复核通过"}
                """))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(released.getResponse().getContentAsString()));
        // 同 requestId 异参：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content("""
                {"requestId":"q3","toVersion":"2.0.0","operatorId":"op-c","reason":"另一个原因"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 已解除再次解除（新 requestId）：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content("""
                {"requestId":"q4","toVersion":"2.0.0","operatorId":"op-d","reason":"重复解除"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_QUARANTINED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_failure_round", Long.class)).isEqualTo(1);

        // 解除只开启新一轮投放：取消 R2 -> R3，可领取新任务
        cancelRelease(r2, "x2");
        long r3 = createRelease("c3", "1.0.0", "2.0.0");
        long t3 = pull("d1", "p4");
        assertThat(t3).isPositive();

        // 新轮第一次失败：roundNo=2 独立计数，历史轮次1保持 RELEASED 且旧两次失败不重算
        receiptFailed(t3, "f3", "新轮失败C", "op-a");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.failureRounds.length()").value(2))
                .andExpect(jsonPath("$.failureRounds[0].roundNo").value(1))
                .andExpect(jsonPath("$.failureRounds[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.failureRounds[0].consecutiveFailed").value(2))
                .andExpect(jsonPath("$.failureRounds[0].firstTaskId").value(t1))
                .andExpect(jsonPath("$.failureRounds[0].secondTaskId").value(t2))
                .andExpect(jsonPath("$.failureRounds[1].roundNo").value(2))
                .andExpect(jsonPath("$.failureRounds[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.failureRounds[1].consecutiveFailed").value(1))
                .andExpect(jsonPath("$.failureRounds[1].firstTaskId").value(t3));
        mockMvc.perform(get("/api/releases/" + r3 + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("FAILED"));
    }

    @Test
    void 回执幂等_同键重放不重复计数_换任务409_失败原因参与指纹() throws Exception {
        registerDevice("d1");
        long r1 = createRelease("c1", "1.0.0", "2.0.0");
        long t1 = pull("d1", "p1");
        receiptFailed(t1, "f1", "原因A", "op-a");
        cancelRelease(r1, "x1");
        long r2 = createRelease("c2", "1.0.0", "2.0.0");
        long t2 = pull("d1", "p2");

        // 第二次失败首次提交
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json").content("""
                {"requestId":"f2","result":"FAILED","failureReason":"原因B","operatorId":"op-b"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // 同 requestId 同参重放：计数与状态不变，仍只隔离一次
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json").content("""
                {"requestId":"f2","result":"FAILED","failureReason":"原因B","operatorId":"op-b"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(jdbc.queryForObject("SELECT consecutive_failed FROM device_failure_round"
                + " WHERE device_id = 'd1' AND to_version = '2.0.0' AND status = 'QUARANTINED'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_failure_round", Long.class)).isEqualTo(1);

        // 同一 requestId 换任务提交：409，且不产生任何副作用
        registerDevice("d2");
        long tOther = pull("d2", "p3");
        mockMvc.perform(post("/api/tasks/" + tOther + "/receipt").contentType("application/json").content("""
                {"requestId":"f2","result":"FAILED","failureReason":"原因B","operatorId":"op-b"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE id = ? AND status = 'PENDING'",
                Long.class, tOther)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_failure_round WHERE device_id = 'd2'",
                Long.class)).isZero();

        // 同 requestId 不同失败原因（仍打原任务）：409
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json").content("""
                {"requestId":"f2","result":"FAILED","failureReason":"篡改原因","operatorId":"op-b"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 一次失败不拦截_版本计数独立_未隔离解除409_设备不存在404() throws Exception {
        registerDevice("d1");
        long r1 = createRelease("c1", "1.0.0", "2.0.0");
        long t1 = pull("d1", "p1");
        receiptFailed(t1, "f1", "原因A", "op-a");

        // 仅一次失败（ACTIVE 轮次）：同版本新单仍可领取
        cancelRelease(r1, "x1");
        long r2 = createRelease("c2", "1.0.0", "2.0.0");
        long t2 = pull("d1", "p2");
        assertThat(t2).isPositive();

        // 对 2.0.0 完成第二次失败隔离
        receiptFailed(t2, "f2", "原因B", "op-b");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));

        // 版本变化独立计数：取消 R2 后投放另一目标版本 3.0.0，不被 2.0.0 的隔离拦截
        cancelRelease(r2, "x2");
        long r3 = createRelease("c3", "1.0.0", "3.0.0");
        long t3 = pull("d1", "p4");
        assertThat(t3).isPositive();
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.failureRounds.length()").value(1));

        // 未隔离的版本解除：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content("""
                {"requestId":"q1","toVersion":"3.0.0","operatorId":"op-c","reason":"无隔离"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_QUARANTINED"));
        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/quarantine/release").contentType("application/json").content("""
                {"requestId":"q2","toVersion":"2.0.0","operatorId":"op-c","reason":"x"}
                """))
                .andExpect(status().isNotFound());
    }
}
