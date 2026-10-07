package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.LeaseAcquireRequest;
import com.example.starter.firmware.api.LeaseRenewRequest;
import com.example.starter.firmware.api.LeaseReceiptRequest;
import com.example.starter.firmware.api.LeaseView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务执行租约测试（真实 H2 事务，MODE=MySQL）：领取/接管/续租/回执的状态与边界、
 * 旧代理接管后写回失败、幂等重放、取消串行校验与真实并发。时钟可注入，不做长时间休眠。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(LeaseTestClock.Config.class)
class FirmwareLeaseTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM task_lease");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        LeaseTestClock.Config.CLOCK.reset();
    }

    private long setupTask(String requestPrefix, String deviceId, String model) {
        deviceService.register(new RegisterDeviceRequest(requestPrefix + "-dev", deviceId, model, "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest(requestPrefix + "-rel", model, "1.0.0", "2.0.0", 100)).releaseId();
        return taskService.pull(deviceId, requestPrefix + "-pull").task().taskId();
    }

    private static ApiException expectConflict(Runnable action, String code) {
        try {
            action.run();
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo(code);
            return e;
        }
        throw new AssertionError("期望 409 " + code + "，但未抛异常");
    }

    @Test
    void 领取_首领token1与30秒到期_查询不自动领取() {
        long taskId = setupTask("r1", "d1", "m1");

        // 未领取时查询返回 409，拉取本身不产生租约
        expectConflict(() -> taskService.getLease(taskId), "LEASE_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_lease", Long.class)).isZero();

        LeaseView lease = taskService.acquireLease(taskId, new LeaseAcquireRequest("r1-acq", "P"));
        assertThat(lease.taskId()).isEqualTo(taskId);
        assertThat(lease.token()).isEqualTo(1);
        assertThat(lease.agentId()).isEqualTo("P");
        assertThat(lease.terminal()).isFalse();
        assertThat(lease.expiresAtUtc()).isEqualTo("2026-10-08T00:00:30Z");
    }

    @Test
    void 有效期_他人不能抢占_本人重放返回首次租约且不延期() {
        long taskId = setupTask("r2", "d1", "m1");
        LeaseView first = taskService.acquireLease(taskId, new LeaseAcquireRequest("r2-acq", "P"));

        LeaseTestClock.Config.CLOCK.advanceSeconds(29);
        // 到期前 1 秒：其他代理 409
        expectConflict(() -> taskService.acquireLease(taskId, new LeaseAcquireRequest("r2-q1", "Q")),
                "LEASE_HELD_BY_OTHER");
        // 本人同 requestId 同参重放：首次完整响应，token/到期时刻不变
        LeaseView replaySameRequest = taskService.acquireLease(taskId, new LeaseAcquireRequest("r2-acq", "P"));
        assertThat(replaySameRequest).isEqualTo(first);
        // 本人新 requestId 重放领取：仍返回首次租约，不延期、不递增 token
        LeaseView replayNewRequest = taskService.acquireLease(taskId, new LeaseAcquireRequest("r2-acq2", "P"));
        assertThat(replayNewRequest.token()).isEqualTo(1);
        assertThat(replayNewRequest.expiresAtUtc()).isEqualTo("2026-10-08T00:00:30Z");

        // 左闭右开：恰好在到期时刻，Q 可接管得到 token2
        LeaseTestClock.Config.CLOCK.advanceSeconds(1);
        LeaseView taken = taskService.acquireLease(taskId, new LeaseAcquireRequest("r2-q2", "Q"));
        assertThat(taken.token()).isEqualTo(2);
        assertThat(taken.agentId()).isEqualTo("Q");
        assertThat(taken.expiresAtUtc()).isEqualTo("2026-10-08T00:01:00Z");
    }

    @Test
    void P领取后到期Q接管_P回执409且不改设备统计_Q成功才更新固件并计一个样本() {
        long taskId = setupTask("r3", "d1", "m1");
        long releaseId = jdbc.queryForObject("SELECT id FROM release_order", Long.class);
        LeaseView pLease = taskService.acquireLease(taskId, new LeaseAcquireRequest("r3-p", "P"));
        assertThat(pLease.token()).isEqualTo(1);

        // 30 秒到期后 Q 领取 token2
        LeaseTestClock.Config.CLOCK.advanceSeconds(30);
        LeaseView qLease = taskService.acquireLease(taskId, new LeaseAcquireRequest("r3-q", "Q"));
        assertThat(qLease.token()).isEqualTo(2);

        // P 持旧 token1 写回成功：409，设备版本与统计不变
        expectConflict(() -> taskService.leaseReceipt(taskId,
                new LeaseReceiptRequest("r3-prc", "P", 1, ReceiptResult.SUCCESS)), "LEASE_INVALID");
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("1.0.0");
        assertThat(releaseService.monitor(releaseId).roundSuccess()).isZero();
        assertThat(releaseService.monitor(releaseId).roundFailed()).isZero();

        // P 重新领取不能夺回任务（Q 的租约有效），续租旧 token 同样被拒
        expectConflict(() -> taskService.acquireLease(taskId, new LeaseAcquireRequest("r3-p2", "P")),
                "LEASE_HELD_BY_OTHER");
        expectConflict(() -> taskService.renewLease(taskId, new LeaseRenewRequest("r3-prn", "P", 1)),
                "LEASE_NOT_HELD_BY_CALLER");

        // Q 成功回执：更新固件、计一个样本、冻结实际代理与 token
        taskService.leaseReceipt(taskId,
                new LeaseReceiptRequest("r3-qrc", "Q", 2, ReceiptResult.SUCCESS));
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("2.0.0");
        assertThat(releaseService.monitor(releaseId).roundSuccess()).isEqualTo(1);
        LeaseView frozen = taskService.getLease(taskId);
        assertThat(frozen.terminal()).isTrue();
        assertThat(frozen.agentId()).isEqualTo("Q");
        assertThat(frozen.token()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT terminal_agent_id FROM task_lease WHERE task_id = ?", String.class, taskId))
                .isEqualTo("Q");
        assertThat(jdbc.queryForObject(
                "SELECT terminal_token FROM task_lease WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(2);

        // 同 requestId 重放回首次完整响应，但不重复计样本
        var replayed = taskService.leaseReceipt(taskId,
                new LeaseReceiptRequest("r3-qrc", "Q", 2, ReceiptResult.SUCCESS));
        assertThat(replayed.status()).isEqualTo("SUCCESS");
        assertThat(releaseService.monitor(releaseId).roundSuccess()).isEqualTo(1);
        // 新 requestId 再次回执：409，同一 token 终结至多一次
        expectConflict(() -> taskService.leaseReceipt(taskId,
                new LeaseReceiptRequest("r3-qrc2", "Q", 2, ReceiptResult.SUCCESS)), "TASK_TERMINATED");
    }

    @Test
    void 续租_本人当前token未到期顺延30秒_到期旧代非持有人409() {
        long taskId = setupTask("r4", "d1", "m1");
        taskService.acquireLease(taskId, new LeaseAcquireRequest("r4-p", "P"));

        // 他人或错误 token 续租：409
        expectConflict(() -> taskService.renewLease(taskId, new LeaseRenewRequest("r4-q", "Q", 1)),
                "LEASE_NOT_HELD_BY_CALLER");
        expectConflict(() -> taskService.renewLease(taskId, new LeaseRenewRequest("r4-wt", "P", 2)),
                "LEASE_NOT_HELD_BY_CALLER");

        // 本人续租：到期时刻改为当前时刻后 30 秒
        LeaseTestClock.Config.CLOCK.advanceSeconds(10);
        LeaseView renewed = taskService.renewLease(taskId, new LeaseRenewRequest("r4-rn", "P", 1));
        assertThat(renewed.token()).isEqualTo(1);
        assertThat(renewed.agentId()).isEqualTo("P");
        assertThat(renewed.expiresAtUtc()).isEqualTo("2026-10-08T00:00:40Z");

        // 原 30 秒时刻（00:00:30）已因续租而未到期，Q 不能接管
        LeaseTestClock.Config.CLOCK.advanceSeconds(20);
        expectConflict(() -> taskService.acquireLease(taskId, new LeaseAcquireRequest("r4-q2", "Q")),
                "LEASE_HELD_BY_OTHER");

        // 租约到期（00:00:40）后不能续租，只能重新领取
        LeaseTestClock.Config.CLOCK.advanceSeconds(10);
        expectConflict(() -> taskService.renewLease(taskId, new LeaseRenewRequest("r4-late", "P", 1)),
                "LEASE_EXPIRED");
        LeaseView qLease = taskService.acquireLease(taskId, new LeaseAcquireRequest("r4-take", "Q"));
        assertThat(qLease.token()).isEqualTo(2);
        // 旧代理 P 持 token1 续租被拒
        expectConflict(() -> taskService.renewLease(taskId, new LeaseRenewRequest("r4-pold", "P", 1)),
                "LEASE_NOT_HELD_BY_CALLER");

        // 从未领取租约的任务不能续租
        long task2 = setupTask("r4b", "d2", "m2");
        expectConflict(() -> taskService.renewLease(task2, new LeaseRenewRequest("r4-none", "P", 1)),
                "LEASE_NOT_FOUND");
    }

    @Test
    void 终结后不能新领取或续租_CANCELLED全部拒绝且不改设备统计() {
        long taskId = setupTask("r5", "d1", "m1");
        long releaseId = jdbc.queryForObject("SELECT id FROM release_order", Long.class);
        taskService.acquireLease(taskId, new LeaseAcquireRequest("r5-p", "P"));
        taskService.leaseReceipt(taskId,
                new LeaseReceiptRequest("r5-rc", "P", 1, ReceiptResult.FAILED));

        // SUCCESS/FAILED 一次终结：不能再领取，CANCELLED 之外也不允许续租
        expectConflict(() -> taskService.acquireLease(taskId, new LeaseAcquireRequest("r5-new", "Q")),
                "TASK_TERMINATED");
        expectConflict(() -> taskService.renewLease(taskId, new LeaseRenewRequest("r5-rn", "P", 1)),
                "LEASE_NOT_RENEWABLE");
        // 冻结代理仍是 P/token1
        LeaseView frozen = taskService.getLease(taskId);
        assertThat(frozen.terminal()).isTrue();
        assertThat(frozen.agentId()).isEqualTo("P");
        assertThat(frozen.token()).isEqualTo(1);
        // FAILED 不更新设备，样本计一次
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("1.0.0");
        assertThat(releaseService.monitor(releaseId).roundFailed()).isEqualTo(1);

        // 取消场景：发布单取消后任务转 CANCELLED，领取/续租/回执一律 409
        long t2 = setupTask("r5b", "d2", "m9");
        long release2 = jdbc.queryForObject("SELECT id FROM release_order WHERE model = 'm9'", Long.class);
        taskService.acquireLease(t2, new LeaseAcquireRequest("r5b-p", "P"));
        releaseService.cancel(release2, "r5b-cancel");
        expectConflict(() -> taskService.acquireLease(t2, new LeaseAcquireRequest("r5b-q", "Q")),
                "TASK_CANCELLED");
        expectConflict(() -> taskService.renewLease(t2, new LeaseRenewRequest("r5b-rn", "P", 1)),
                "LEASE_NOT_RENEWABLE");
        expectConflict(() -> taskService.leaseReceipt(t2,
                new LeaseReceiptRequest("r5b-rc", "P", 1, ReceiptResult.SUCCESS)), "TASK_CANCELLED");
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("1.0.0");
        assertThat(releaseService.monitor(release2).roundSuccess()).isZero();
    }

    @Test
    void 原回执入口不能绕过租约模式_既有任务原回执规则保持() {
        // 进入租约模式后：原 /receipt 入口 409，不能绕过 agentId/token
        long taskId = setupTask("r6", "d1", "m1");
        taskService.acquireLease(taskId, new LeaseAcquireRequest("r6-p", "P"));
        expectConflict(() -> taskService.receipt(taskId,
                new com.example.starter.firmware.api.ReceiptRequest("r6-old", ReceiptResult.SUCCESS)),
                "LEASE_MODE_REQUIRED");
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("1.0.0");

        // 未进入租约模式的既有任务：原回执入口照常工作（暂停允许既有任务回执的规则保持）
        long t2 = setupTask("r6b", "d2", "m2");
        taskService.receipt(t2, new com.example.starter.firmware.api.ReceiptRequest("r6b-rc", ReceiptResult.SUCCESS));
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("2.0.0");
        // 同结果重复回执照旧成功
        taskService.receipt(t2, new com.example.starter.firmware.api.ReceiptRequest("r6b-rc2", ReceiptResult.SUCCESS));
    }

    @Test
    void 幂等_同参回首次响应_异参409_失败不占键_接管重放不恢复有效性() {
        long taskId = setupTask("r7", "d1", "m1");
        LeaseView first = taskService.acquireLease(taskId, new LeaseAcquireRequest("r7-p", "P"));

        // 同 requestId 同参重放：首次完整响应
        assertThat(taskService.acquireLease(taskId, new LeaseAcquireRequest("r7-p", "P"))).isEqualTo(first);
        // 同 requestId 异参（不同 agentId）：409
        expectConflict(() -> taskService.acquireLease(taskId, new LeaseAcquireRequest("r7-p", "Q")),
                "REQUEST_ID_CONFLICT");

        // 失败不占键：Q 在 P 有效期内领取被 409，到期后同 requestId 可成功接管
        expectConflict(() -> taskService.acquireLease(taskId, new LeaseAcquireRequest("r7-q", "Q")),
                "LEASE_HELD_BY_OTHER");
        LeaseTestClock.Config.CLOCK.advanceSeconds(30);
        LeaseView taken = taskService.acquireLease(taskId, new LeaseAcquireRequest("r7-q", "Q"));
        assertThat(taken.token()).isEqualTo(2);

        // 旧成功领取重放（P 的首次响应）返回首次租约快照，但不恢复其有效性：P 回执仍 409
        assertThat(taskService.acquireLease(taskId, new LeaseAcquireRequest("r7-p", "P"))).isEqualTo(first);
        expectConflict(() -> taskService.leaseReceipt(taskId,
                new LeaseReceiptRequest("r7-prc", "P", 1, ReceiptResult.SUCCESS)), "LEASE_INVALID");

        // 租约回执幂等：同 requestId 异参 409；同参重放不重复计样本
        long releaseId = jdbc.queryForObject("SELECT id FROM release_order", Long.class);
        taskService.leaseReceipt(taskId,
                new LeaseReceiptRequest("r7-qrc", "Q", 2, ReceiptResult.SUCCESS));
        expectConflict(() -> taskService.leaseReceipt(taskId,
                new LeaseReceiptRequest("r7-qrc", "Q", 2, ReceiptResult.FAILED)), "REQUEST_ID_CONFLICT");
        assertThat(releaseService.monitor(releaseId).roundSuccess()).isEqualTo(1);
    }

    @Test
    void HTTP接口_领取续租接管回执全链路_参数校验400() throws Exception {
        long taskId = setupTask("r8", "d1", "m1");

        // agentId 为空：400
        mockMvc.perform(post("/api/tasks/" + taskId + "/leases").contentType("application/json")
                        .content("{\"requestId\":\"r8-bad\",\"agentId\":\"\"}"))
                .andExpect(status().isBadRequest());
        // token 非正数：400
        mockMvc.perform(post("/api/tasks/" + taskId + "/leases/renew").contentType("application/json")
                        .content("{\"requestId\":\"r8-bad2\",\"agentId\":\"P\",\"token\":0}"))
                .andExpect(status().isBadRequest());

        // 领取 token1
        mockMvc.perform(post("/api/tasks/" + taskId + "/leases").contentType("application/json")
                        .content("{\"requestId\":\"r8-p\",\"agentId\":\"P\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(1))
                .andExpect(jsonPath("$.agentId").value("P"))
                .andExpect(jsonPath("$.terminal").value(false))
                .andExpect(jsonPath("$.expiresAtUtc").value("2026-10-08T00:00:30Z"));

        // Q 在有效期内接管：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/leases").contentType("application/json")
                        .content("{\"requestId\":\"r8-q0\",\"agentId\":\"Q\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_HELD_BY_OTHER"));

        // 查询只读
        mockMvc.perform(get("/api/tasks/" + taskId + "/lease"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(1));

        // 到期后 Q 接管
        LeaseTestClock.Config.CLOCK.advanceSeconds(30);
        mockMvc.perform(post("/api/tasks/" + taskId + "/leases").contentType("application/json")
                        .content("{\"requestId\":\"r8-q\",\"agentId\":\"Q\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(2))
                .andExpect(jsonPath("$.agentId").value("Q"));

        // P 旧 token 回执：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease-receipt").contentType("application/json")
                        .content("{\"requestId\":\"r8-prc\",\"agentId\":\"P\",\"token\":1,\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_INVALID"));
        // Q 成功回执
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease-receipt").contentType("application/json")
                        .content("{\"requestId\":\"r8-qrc\",\"agentId\":\"Q\",\"token\":2,\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/tasks/" + taskId + "/lease"))
                .andExpect(jsonPath("$.terminal").value(true))
                .andExpect(jsonPath("$.agentId").value("Q"))
                .andExpect(jsonPath("$.token").value(2));

        // 任务不存在：404
        mockMvc.perform(post("/api/tasks/9999/leases").contentType("application/json")
                        .content("{\"requestId\":\"r8-404\",\"agentId\":\"P\"}"))
                .andExpect(status().isNotFound());
    }
}
