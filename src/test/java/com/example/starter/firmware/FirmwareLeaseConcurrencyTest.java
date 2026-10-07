package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.LeaseAcquireRequest;
import com.example.starter.firmware.api.LeaseReceiptRequest;
import com.example.starter.firmware.api.LeaseRenewRequest;
import com.example.starter.firmware.api.LeaseView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租约并发测试：真实并发打到 H2（MODE=MySQL）事务与行锁上，
 * 验证接管、回执、取消、续租的串行校验与"同一 token 终结至多一次"。
 * 时钟固定在可控时刻，到期场景用时钟推进模拟，不做真实休眠。
 */
@SpringBootTest
@Import(LeaseTestClock.Config.class)
class FirmwareLeaseConcurrencyTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM task_lease");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        LeaseTestClock.Config.CLOCK.reset();
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private long setupTask(String prefix, String deviceId, String model) {
        deviceService.register(new RegisterDeviceRequest(prefix + "-dev", deviceId, model, "1.0.0", 1));
        releaseService.create(new CreateReleaseRequest(prefix + "-rel", model, "1.0.0", "2.0.0", 100));
        return taskService.pull(deviceId, prefix + "-pull").task().taskId();
    }

    /**
     * 闩锁对齐后并发执行，收集成功结果与异常（异常作为结果返回，不吞掉）。
     */
    private List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<?> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private static boolean conflict(Object result, String code) {
        return result instanceof ApiException ae
                && ae.status() == HttpStatus.CONFLICT && ae.code().equals(code);
    }

    @Test
    void 并发到期接管_恰一个代理拿到新token_另一个409且租约只一行() throws Exception {
        long taskId = setupTask("c1", "d1", "m1");
        taskService.acquireLease(taskId, new LeaseAcquireRequest("c1-p", "P"));
        LeaseTestClock.Config.CLOCK.advanceSeconds(30);

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.acquireLease(taskId, new LeaseAcquireRequest("c1-q", "Q")),
                (Callable<Object>) () -> taskService.acquireLease(taskId, new LeaseAcquireRequest("c1-r", "R"))));

        long success = results.stream().filter(r -> r instanceof LeaseView).count();
        long blocked = results.stream().filter(r -> conflict(r, "LEASE_HELD_BY_OTHER")).count();
        assertThat(success).isEqualTo(1);
        assertThat(blocked).isEqualTo(1);
        LeaseView winner = results.stream().filter(r -> r instanceof LeaseView)
                .map(r -> (LeaseView) r).findFirst().orElseThrow();
        assertThat(winner.token()).isEqualTo(2);
        // 租约始终只有一行，且令牌恰递增一次
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_lease", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_token FROM task_lease WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_agent_id FROM task_lease WHERE task_id = ?",
                String.class, taskId)).isEqualTo(winner.agentId());
        // 未抢到的代理不能写回
        String loser = winner.agentId().equals("Q") ? "R" : "Q";
        try {
            taskService.leaseReceipt(taskId,
                    new LeaseReceiptRequest("c1-loser", loser, 2, ReceiptResult.SUCCESS));
            throw new AssertionError("非持有人回执必须 409");
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo("LEASE_INVALID");
        }
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("1.0.0");
    }

    @Test
    void 并发同一token回执_恰一次终结计一个样本_其余409且设备只更新一次() throws Exception {
        long taskId = setupTask("c2", "d1", "m1");
        long releaseId = jdbc.queryForObject("SELECT id FROM release_order", Long.class);
        taskService.acquireLease(taskId, new LeaseAcquireRequest("c2-p", "P"));

        int threads = 6;
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.leaseReceipt(taskId,
                    new LeaseReceiptRequest("c2-rc" + seq, "P", 1, ReceiptResult.SUCCESS)));
        }
        List<Object> results = runConcurrently(tasks);

        long success = results.stream().filter(r -> r instanceof TaskView).count();
        long rejected = results.stream().filter(r -> conflict(r, "TASK_TERMINATED")).count();
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        // 业务最终结果：设备只更新一次、统计只有一个样本、冻结 P/token1
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("2.0.0");
        ReleaseOrder order = releaseService.findOrder(releaseId);
        assertThat(order.roundSuccess()).isEqualTo(1);
        assertThat(order.roundFailed()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT terminal_agent_id FROM task_lease WHERE task_id = ?",
                String.class, taskId)).isEqualTo("P");
        assertThat(jdbc.queryForObject("SELECT terminal_token FROM task_lease WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);
    }

    @Test
    void 并发接管与旧代理回执_按提交顺序唯一结局且最终一致() throws Exception {
        for (int round = 0; round < 10; round++) {
            String prefix = "c3-" + round;
            String deviceId = "d" + round;
            String model = "m" + round;
            long taskId = setupTask(prefix, deviceId, model);
            long releaseId = jdbc.queryForObject("SELECT id FROM release_order WHERE model = ?",
                    Long.class, model);
            taskService.acquireLease(taskId, new LeaseAcquireRequest(prefix + "-p", "P"));
            LeaseTestClock.Config.CLOCK.advanceSeconds(30 + round);

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.acquireLease(taskId,
                            new LeaseAcquireRequest(prefix + "-q", "Q")),
                    (Callable<Object>) () -> taskService.leaseReceipt(taskId,
                            new LeaseReceiptRequest(prefix + "-prc", "P", 1, ReceiptResult.SUCCESS))));

            Object takeOver = results.get(0);
            Object oldReceipt = results.get(1);
            String version = deviceService.get(deviceId).currentVersion();
            Integer token = jdbc.queryForObject("SELECT current_token FROM task_lease WHERE task_id = ?",
                    Integer.class, taskId);
            Integer terminalToken = jdbc.queryForObject("SELECT terminal_token FROM task_lease WHERE task_id = ?",
                    Integer.class, taskId);
            String status = jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                    String.class, taskId);
            if (takeOver instanceof LeaseView lv) {
                // Q 接管先提交：P 的旧 token 回执必为 409，任务仍 PENDING，设备与统计不变
                assertThat(lv.token()).isEqualTo(2);
                assertThat(conflict(oldReceipt, "LEASE_INVALID")).isTrue();
                assertThat(status).isEqualTo("PENDING");
                assertThat(version).isEqualTo("1.0.0");
                assertThat(terminalToken).isNull();
                assertThat(releaseService.findOrder(releaseId).roundSuccess()).isZero();
                // 接管成立后 Q 可成功回执
                taskService.leaseReceipt(taskId,
                        new LeaseReceiptRequest(prefix + "-qrc", "Q", 2, ReceiptResult.SUCCESS));
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
                assertThat(releaseService.findOrder(releaseId).roundSuccess()).isEqualTo(1);
            } else {
                // P 回执先提交：任务已被 P/token1 终结，Q 接管必为 409
                assertThat(conflict(takeOver, "TASK_TERMINATED")).isTrue();
                assertThat(oldReceipt).isInstanceOf(TaskView.class);
                assertThat(status).isEqualTo("SUCCESS");
                assertThat(version).isEqualTo("2.0.0");
                assertThat(token).isEqualTo(1);
                assertThat(terminalToken).isEqualTo(1);
            }
        }
    }

    @Test
    void 并发租约回执与取消_恰一种结局且已成功不回滚() throws Exception {
        int receiptWins = 0;
        int cancelWins = 0;
        for (int round = 0; round < 10; round++) {
            String prefix = "c4-" + round;
            String deviceId = "d" + round;
            String model = "m" + round;
            long taskId = setupTask(prefix, deviceId, model);
            long releaseId = jdbc.queryForObject("SELECT id FROM release_order WHERE model = ?",
                    Long.class, model);
            taskService.acquireLease(taskId, new LeaseAcquireRequest(prefix + "-p", "P"));

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.leaseReceipt(taskId,
                            new LeaseReceiptRequest(prefix + "-rc", "P", 1, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, prefix + "-cancel")));

            Object receiptResult = results.get(0);
            String taskStatus = jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                    String.class, taskId);
            if (taskStatus.equals("SUCCESS")) {
                receiptWins++;
                assertThat(receiptResult).isInstanceOf(TaskView.class);
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
                assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.CANCELLED);
            } else {
                cancelWins++;
                assertThat(taskStatus).isEqualTo("CANCELLED");
                assertThat(conflict(receiptResult, "TASK_CANCELLED")).isTrue();
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("1.0.0");
            }
            assertThat(releaseService.findOrder(releaseId).roundSuccess())
                    .isEqualTo(taskStatus.equals("SUCCESS") ? 1 : 0);
        }
        assertThat(receiptWins + cancelWins).isEqualTo(10);
    }

    @Test
    void 并发同requestId同参领取_全部返回首次租约且不递增token() throws Exception {
        long taskId = setupTask("c5", "d1", "m1");

        int threads = 6;
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.acquireLease(taskId, new LeaseAcquireRequest("c5-same", "P")));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOf(LeaseView.class);
            LeaseView view = (LeaseView) result;
            assertThat(view.token()).isEqualTo(1);
            assertThat(view.agentId()).isEqualTo("P");
            assertThat(view.expiresAtUtc()).isEqualTo("2026-10-08T00:00:30Z");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_lease", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_token FROM task_lease WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);
    }

    @Test
    void 并发同requestId回执_重放首次响应且副作用只一次() throws Exception {
        long taskId = setupTask("c6", "d1", "m1");
        long releaseId = jdbc.queryForObject("SELECT id FROM release_order", Long.class);
        taskService.acquireLease(taskId, new LeaseAcquireRequest("c6-p", "P"));

        int threads = 6;
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.leaseReceipt(taskId,
                    new LeaseReceiptRequest("c6-same", "P", 1, ReceiptResult.SUCCESS)));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).as("同参并发为重放，不抛异常").isInstanceOf(TaskView.class);
            assertThat(((TaskView) result).status()).isEqualTo(TaskStatus.SUCCESS.name());
        }
        assertThat(releaseService.findOrder(releaseId).roundSuccess()).isEqualTo(1);
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("2.0.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?",
                Long.class, "c6-same")).isEqualTo(1);
    }

    @Test
    void 并发续租与接管_续租只在未到期时成立_到期接管串行唯一() throws Exception {
        long taskId = setupTask("c7", "d1", "m1");
        taskService.acquireLease(taskId, new LeaseAcquireRequest("c7-p", "P"));

        // 未到期：P 续租 与 Q 接管并发，续租可成功，接管必 409
        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.renewLease(taskId,
                        new LeaseRenewRequest("c7-rn", "P", 1)),
                (Callable<Object>) () -> taskService.acquireLease(taskId,
                        new LeaseAcquireRequest("c7-q", "Q"))));
        assertThat(results.get(0)).isInstanceOf(LeaseView.class);
        assertThat(conflict(results.get(1), "LEASE_HELD_BY_OTHER")).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_token FROM task_lease WHERE task_id = ?",
                Integer.class, taskId)).isEqualTo(1);

        // 到期后：P 续租（token1）与 Q 接管并发，接管成立则续租失败，反之亦然；最终 token 恰为 2
        LeaseTestClock.Config.CLOCK.advanceSeconds(60);
        List<Object> expired = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.renewLease(taskId,
                        new LeaseRenewRequest("c7-rn2", "P", 1)),
                (Callable<Object>) () -> taskService.acquireLease(taskId,
                        new LeaseAcquireRequest("c7-q2", "Q"))));
        long leaseViewCount = expired.stream().filter(r -> r instanceof LeaseView).count();
        // 到期时续租必失败：P 先拿到锁则 LEASE_EXPIRED；Q 接管先提交则 P 已非持有人。仅 Q 接管成功
        assertThat(leaseViewCount).isEqualTo(1);
        assertThat(expired.get(1)).isInstanceOf(LeaseView.class);
        LeaseView survivor = (LeaseView) expired.get(1);
        assertThat(survivor.agentId()).isEqualTo("Q");
        assertThat(survivor.token()).isEqualTo(2);
        assertThat(conflict(expired.get(0), "LEASE_EXPIRED")
                || conflict(expired.get(0), "LEASE_NOT_HELD_BY_CALLER")).isTrue();
        // 旧代理回执终被拒
        try {
            taskService.leaseReceipt(taskId,
                    new LeaseReceiptRequest("c7-prc", "P", 1, ReceiptResult.SUCCESS));
            throw new AssertionError("旧代理接管后回执必须 409");
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo("LEASE_INVALID");
        }
    }
}
