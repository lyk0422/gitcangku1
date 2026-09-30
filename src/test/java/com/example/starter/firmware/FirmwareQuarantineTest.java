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
 * 设备隔离主流程、失败分支与幂等边界 API 测试（H2 内存库，MODE=MySQL）：
 * 连续两次失败隔离、隔离期 409、异人审核解除、历史保留、版本独立、回执幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareQuarantineTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM device_quarantine_round");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void registerDevice(String requestId, String deviceId) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(requestId, deviceId, deviceId)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"%s","toVersion":"%s","ratio":100}
                """.formatted(requestId, from, to)))
                .andExpect(status().isOk())
                .andReturn();
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.releaseId");
        return value.longValue();
    }

    private long pull(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.task.taskId");
        return value.longValue();
    }

    private void cancel(String requestId, long releaseId) throws Exception {
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk());
    }

    private String receiptBody(String requestId, String result, String reason, String operator) {
        StringBuilder sb = new StringBuilder("{\"requestId\":\"").append(requestId)
                .append("\",\"result\":\"").append(result).append("\"");
        if (reason != null) {
            sb.append(",\"reason\":\"").append(reason).append("\"");
        }
        if (operator != null) {
            sb.append(",\"operator\":\"").append(operator).append("\"");
        }
        return sb.append("}").toString();
    }

    @Test
    void 连续两次失败后隔离_固化两次任务版本原因_隔离期拉取409_异人解除开新轮且历史保留() throws Exception {
        registerDevice("r-d", "d1");
        long releaseA = createRelease("r-a", "1.0.0", "2.0.0");
        long t1 = pull("r-p1", "d1");

        // 第一次失败：TRACKING，计数1，固化第一次任务/发布单/原因
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content(receiptBody("r-f1", "FAILED", "校验失败E1", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("校验失败E1"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantineRounds.length()").value(1))
                .andExpect(jsonPath("$.quarantineRounds[0].targetVersion").value("2.0.0"))
                .andExpect(jsonPath("$.quarantineRounds[0].roundNo").value(1))
                .andExpect(jsonPath("$.quarantineRounds[0].consecutiveFailures").value(1))
                .andExpect(jsonPath("$.quarantineRounds[0].status").value("TRACKING"))
                .andExpect(jsonPath("$.quarantineRounds[0].firstTaskId").value(t1))
                .andExpect(jsonPath("$.quarantineRounds[0].firstReleaseId").value(releaseA))
                .andExpect(jsonPath("$.quarantineRounds[0].firstReason").value("校验失败E1"))
                .andExpect(jsonPath("$.quarantineRounds[0].secondTaskId").doesNotExist())
                .andExpect(jsonPath("$.quarantineRounds[0].confirmedBy").doesNotExist());

        // 第二次失败需要一张新发布单（同设备同发布单唯一任务，失败任务不重开）
        cancel("r-cancel-a", releaseA);
        long releaseB = createRelease("r-b", "1.0.0", "2.0.0");
        long t2 = pull("r-p2", "d1");

        // 第二次失败缺操作人：400，整笔回执回滚（任务仍 PENDING、未隔离、不占 requestId）
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content(receiptBody("r-f2-null-op", "FAILED", "升级失败E2", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPERATOR_REQUIRED"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM rollout_task WHERE id = ?", String.class, t2)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_quarantine_round WHERE status = 'QUARANTINED'",
                Long.class)).isZero();

        // 第二次失败带操作人：隔离固化两次任务/版本/原因/确认人；发布单失败统计继续累计
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content(receiptBody("r-f2", "FAILED", "升级失败E2", "alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("升级失败E2"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.quarantineRounds.length()").value(1))
                .andExpect(jsonPath("$.quarantineRounds[0].status").value("QUARANTINED"))
                .andExpect(jsonPath("$.quarantineRounds[0].consecutiveFailures").value(2))
                .andExpect(jsonPath("$.quarantineRounds[0].firstTaskId").value(t1))
                .andExpect(jsonPath("$.quarantineRounds[0].firstReason").value("校验失败E1"))
                .andExpect(jsonPath("$.quarantineRounds[0].secondTaskId").value(t2))
                .andExpect(jsonPath("$.quarantineRounds[0].secondReleaseId").value(releaseB))
                .andExpect(jsonPath("$.quarantineRounds[0].secondReason").value("升级失败E2"))
                .andExpect(jsonPath("$.quarantineRounds[0].confirmedBy").value("alice"))
                .andExpect(jsonPath("$.quarantineRounds[0].quarantinedAtUtc").isString());
        mockMvc.perform(get("/api/releases/" + releaseB + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));

        // 隔离期拉取：409
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull-quarantined\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));

        // 解除审核人与确认人相同：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content("""
                {"requestId":"r-rel-same","targetVersion":"2.0.0","reviewer":"alice","reason":"自查通过"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEWER_SAME_AS_CONFIRMER"));

        // 不同审核人解除：旧轮 RELEASED 并记录解除信息，另开一轮 count=0 的 TRACKING
        MvcResult released = mockMvc.perform(post("/api/devices/d1/quarantine/release")
                        .contentType("application/json").content("""
                                {"requestId":"r-rel-ok","targetVersion":"2.0.0","reviewer":"bob","reason":"现场复核通过"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedBy").value("bob"))
                .andExpect(jsonPath("$.releaseReason").value("现场复核通过"))
                .andExpect(jsonPath("$.releasedAtUtc").isString())
                .andExpect(jsonPath("$.nextRoundId").isNumber())
                .andReturn();
        Number nextRoundValue = com.jayway.jsonpath.JsonPath.read(
                released.getResponse().getContentAsString(), "$.nextRoundId");
        long nextRoundId = nextRoundValue.longValue();

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.quarantineRounds.length()").value(2))
                .andExpect(jsonPath("$.quarantineRounds[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.quarantineRounds[0].consecutiveFailures").value(2))
                .andExpect(jsonPath("$.quarantineRounds[0].firstTaskId").value(t1))
                .andExpect(jsonPath("$.quarantineRounds[0].secondTaskId").value(t2))
                .andExpect(jsonPath("$.quarantineRounds[0].confirmedBy").value("alice"))
                .andExpect(jsonPath("$.quarantineRounds[0].releasedBy").value("bob"))
                .andExpect(jsonPath("$.quarantineRounds[0].nextRoundId").value(nextRoundId))
                .andExpect(jsonPath("$.quarantineRounds[1].id").value(nextRoundId))
                .andExpect(jsonPath("$.quarantineRounds[1].status").value("TRACKING"))
                .andExpect(jsonPath("$.quarantineRounds[1].roundNo").value(2))
                .andExpect(jsonPath("$.quarantineRounds[1].consecutiveFailures").value(0))
                .andExpect(jsonPath("$.quarantineRounds[1].firstTaskId").doesNotExist());

        // 已解除再次解除：409（当前活跃轮次未隔离）
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content("""
                {"requestId":"r-rel-again","targetVersion":"2.0.0","reviewer":"carol","reason":"重复解除"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_QUARANTINED"));
        // 对不存在隔离的版本解除：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json").content("""
                {"requestId":"r-rel-other-v","targetVersion":"9.9.9","reviewer":"bob","reason":"无"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_QUARANTINED"));

        // 解除后开启新一轮投放：旧发布单已终结，新建发布单可领取新任务
        cancel("r-cancel-b", releaseB);
        long releaseC = createRelease("r-c", "1.0.0", "2.0.0");
        long t3 = pull("r-p3", "d1");
        assertThat(t3).isNotEqualTo(t1).isNotEqualTo(t2);
        assertThat(jdbc.queryForObject("SELECT release_id FROM rollout_task WHERE id = ?", Long.class, t3))
                .isEqualTo(releaseC);
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, t3))
                .isEqualTo("PENDING");
        // 旧两次失败历史仍可查，未被清除
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_quarantine_round WHERE status = 'RELEASED' AND consecutive_failures = 2",
                Long.class)).isEqualTo(1);
    }

    @Test
    void 失败回执幂等_同键重放首次计数和状态_换任务409_第二次失败重放不重复隔离() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long releaseA = createRelease("r-a", "1.0.0", "2.0.0");
        long t1 = pull("r-p1", "d1");
        long otherTask = pull("r-p-other", "d2");

        // 第一次失败回执同键同参重放：计数仍为1，响应一致
        MvcResult first = mockMvc.perform(post("/api/tasks/" + t1 + "/receipt")
                        .contentType("application/json")
                        .content(receiptBody("req-f1", "FAILED", "E1", null)))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content(receiptBody("req-f1", "FAILED", "E1", null)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        assertThat(jdbc.queryForObject(
                "SELECT consecutive_failures FROM device_quarantine_round WHERE device_id = 'd1'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_quarantine_round", Long.class)).isEqualTo(1);

        // 同一 requestId 换任务回执：409（指纹不同，任务业务不再执行）
        mockMvc.perform(post("/api/tasks/" + otherTask + "/receipt").contentType("application/json")
                        .content(receiptBody("req-f1", "FAILED", "E1", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, otherTask))
                .isEqualTo("PENDING");

        // 第二次失败隔离后同键同参重放：仍只一条 QUARANTINED，计数保持2
        cancel("r-ca", releaseA);
        long releaseB = createRelease("r-b", "1.0.0", "2.0.0");
        long t2 = pull("r-p2", "d1");
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content(receiptBody("req-f2", "FAILED", "E2", "alice")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content(receiptBody("req-f2", "FAILED", "E2", "alice")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_quarantine_round WHERE device_id = 'd1' AND status = 'QUARANTINED'",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT consecutive_failures FROM device_quarantine_round WHERE device_id = 'd1' AND status = 'QUARANTINED'",
                Integer.class)).isEqualTo(2);

        // 同键异参（改原因）：409
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content(receiptBody("req-f2", "FAILED", "另一个原因", "alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 成功中断本轮连续失败_计数清零且首次失败依据保留() throws Exception {
        registerDevice("r-d", "d3");
        long releaseA = createRelease("r-a", "1.0.0", "2.0.0");
        long t1 = pull("r-p1", "d3");
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content(receiptBody("r-f1", "FAILED", "偶发失败", null)))
                .andExpect(status().isOk());
        cancel("r-ca", releaseA);
        long releaseB = createRelease("r-b", "1.0.0", "2.0.0");
        long t2 = pull("r-p2", "d3");

        // 第二次任务成功：连续失败中断，计数清零，设备升级；首次失败依据作为历史保留
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content(receiptBody("r-s2", "SUCCESS", null, null)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d3"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.quarantineRounds.length()").value(1))
                .andExpect(jsonPath("$.quarantineRounds[0].status").value("TRACKING"))
                .andExpect(jsonPath("$.quarantineRounds[0].consecutiveFailures").value(0))
                .andExpect(jsonPath("$.quarantineRounds[0].firstTaskId").value(t1))
                .andExpect(jsonPath("$.quarantineRounds[0].firstReason").value("偶发失败"))
                .andExpect(jsonPath("$.quarantineRounds[0].secondTaskId").doesNotExist());
    }

    @Test
    void 版本变化时新一轮计数独立_历史不重算() throws Exception {
        registerDevice("r-d", "d4");
        // 1.0.0 -> 2.0.0 一次成功
        long releaseA = createRelease("r-a", "1.0.0", "2.0.0");
        long t1 = pull("r-p1", "d4");
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content(receiptBody("r-s1", "SUCCESS", null, null)))
                .andExpect(status().isOk());
        cancel("r-ca", releaseA);

        // 2.0.0 -> 3.0.0 第一次失败：按 3.0.0 维度独立计数，与 2.0.0 无关
        long releaseB = createRelease("r-b", "2.0.0", "3.0.0");
        long t2 = pull("r-p2", "d4");
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content(receiptBody("r-fb1", "FAILED", "3.0.0失败", null)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d4"))
                .andExpect(jsonPath("$.quarantineRounds.length()").value(1))
                .andExpect(jsonPath("$.quarantineRounds[0].targetVersion").value("3.0.0"))
                .andExpect(jsonPath("$.quarantineRounds[0].consecutiveFailures").value(1))
                .andExpect(jsonPath("$.quarantineRounds[0].status").value("TRACKING"));

        // 解除后的空轮次不被后续操作重算：构造一次 2.0.0 隔离再解除，RELEASED 轮次计数恒定为2
        cancel("r-cb", releaseB);
        long releaseC = createRelease("r-c", "2.0.0", "3.0.0");
        long t3 = pull("r-p3", "d4");
        mockMvc.perform(post("/api/tasks/" + t3 + "/receipt").contentType("application/json")
                        .content(receiptBody("r-fb2", "FAILED", "3.0.0再失败", "alice")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d4/quarantine/release").contentType("application/json").content("""
                {"requestId":"r-rel3","targetVersion":"3.0.0","reviewer":"bob","reason":"复核通过"}
                """))
                .andExpect(status().isOk());
        // 旧轮 RELEASED 计数恒定2且两次依据齐全；新轮 count=0
        mockMvc.perform(get("/api/devices/d4"))
                .andExpect(jsonPath("$.quarantineRounds.length()").value(2))
                .andExpect(jsonPath("$.quarantineRounds[0].targetVersion").value("3.0.0"))
                .andExpect(jsonPath("$.quarantineRounds[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.quarantineRounds[0].consecutiveFailures").value(2))
                .andExpect(jsonPath("$.quarantineRounds[0].firstTaskId").value(t2))
                .andExpect(jsonPath("$.quarantineRounds[0].secondTaskId").value(t3))
                .andExpect(jsonPath("$.quarantineRounds[1].status").value("TRACKING"))
                .andExpect(jsonPath("$.quarantineRounds[1].consecutiveFailures").value(0));
    }

    @Test
    void 设备不存在_隔离与查询404() throws Exception {
        mockMvc.perform(post("/api/devices/ghost/quarantine/release").contentType("application/json").content("""
                {"requestId":"r-x","targetVersion":"2.0.0","reviewer":"bob","reason":"无"}
                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/devices/ghost")).andExpect(status().isNotFound());
    }
}
