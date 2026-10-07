package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.LeaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RenewLeaseRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import com.example.starter.firmware.support.ClockTestConfiguration;
import com.example.starter.firmware.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
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
 * 执行租约并发测试：真实并发打到 H2 事务与行锁上，验证领取抢占、到期接管、
 * 回执唯一终结、取消/续租串行提交顺序及统计不重复。
 */
@SpringBootTest
@Import(ClockTestConfiguration.class)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:firmware_lease_conc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class FirmwareLeaseConcurrencyTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.setInstant(ClockTestConfiguration.START);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
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

    private long setupTask(String deviceId, String reqPrefix) {
        deviceService.register(new RegisterDeviceRequest(reqPrefix + "-d", deviceId, "m1", "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest(reqPrefix + "-r", "m1", "1.0.0", "2.0.0", 100)).releaseId();
        return taskService.pull(deviceId, reqPrefix + "-p").task().taskId();
    }

    private boolean conflict(Object result, String code) {
        return result instanceof ApiException ae
                && ae.status() == HttpStatus.CONFLICT
                && ae.code().equals(code);
    }

    @Test
    void 并发领取_有效期内仅持有人可得租约_令牌不增加() throws Exception {
        long taskId = setupTask("d1", "s1");
        taskService.acquire(taskId, new LeaseRequest("s1-a-p", "P"));

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> taskService.acquire(taskId, new LeaseRequest("s1-a-p2", "P")));
        for (int i = 0; i < 5; i++) {
            String agent = "Q" + i;
            tasks.add(() -> taskService.acquire(taskId, new LeaseRequest("s1-a-" + agent, agent)));
        }
        List<Object> results = runConcurrently(tasks);

        long holderOk = results.stream()
                .filter(r -> r instanceof com.example.starter.firmware.api.LeaseView lv
                        && lv.agentId().equals("P") && lv.token() == 1).count();
        long preemptRejected = results.stream().filter(r -> conflict(r, "LEASE_HELD_BY_OTHER")).count();
        assertThat(holderOk).isEqualTo(1);
        assertThat(preemptRejected).isEqualTo(5);
        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.leaseAgentId()).isEqualTo("P");
        assertThat(task.leaseToken()).isEqualTo(1);
    }

    @Test
    void 到期并发接管_恰好一个代理领取到递增令牌() throws Exception {
        long taskId = setupTask("d2", "s2");
        taskService.acquire(taskId, new LeaseRequest("s2-a-p", "P"));
        clock.advance(Duration.ofSeconds(30));

        int agents = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < agents; i++) {
            String agent = "A" + i;
            tasks.add(() -> taskService.acquire(taskId, new LeaseRequest("s2-a-" + agent, agent)));
        }
        List<Object> results = runConcurrently(tasks);

        long granted = results.stream()
                .filter(r -> r instanceof com.example.starter.firmware.api.LeaseView lv && lv.token() == 2)
                .count();
        long rejected = results.stream().filter(r -> conflict(r, "LEASE_HELD_BY_OTHER")).count();
        assertThat(granted).as("恰好一个代理接管成功").isEqualTo(1);
        assertThat(rejected).isEqualTo(agents - 1);

        var winner = results.stream()
                .filter(r -> r instanceof com.example.starter.firmware.api.LeaseView)
                .map(r -> (com.example.starter.firmware.api.LeaseView) r)
                .findFirst().orElseThrow();
        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.leaseAgentId()).isEqualTo(winner.agentId());
        assertThat(task.leaseToken()).isEqualTo(2);
        assertThat(task.leaseExpiresAt()).isEqualTo(ClockTestConfiguration.START.plusSeconds(60));
    }

    @Test
    void 接管后新旧代理并发回执_仅当前持有人终结且只计一个样本() throws Exception {
        String deviceId = "d3";
        deviceService.register(new RegisterDeviceRequest("s3-d", deviceId, "m1", "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("s3-r", "m1", "1.0.0", "2.0.0", 100)).releaseId();
        long taskId = taskService.pull(deviceId, "s3-p").task().taskId();
        taskService.acquire(taskId, new LeaseRequest("s3-a-p", "P"));
        clock.advance(Duration.ofSeconds(30));
        taskService.acquire(taskId, new LeaseRequest("s3-a-q", "Q"));

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.receipt(taskId,
                        new ReceiptRequest("s3-rc-p", ReceiptResult.SUCCESS, "P", 1)),
                (Callable<Object>) () -> taskService.receipt(taskId,
                        new ReceiptRequest("s3-rc-q", ReceiptResult.SUCCESS, "Q", 2))));

        long qOk = results.stream()
                .filter(r -> r instanceof com.example.starter.firmware.api.TaskView tv
                        && tv.status().equals("SUCCESS") && tv.resultAgentId().equals("Q")).count();
        // P 先到：任务仍 PENDING，旧令牌被接管 -> LEASE_SUPERSEDED；
        // Q 先终结：任务已 SUCCESS，冻结代理不匹配 -> LEASE_HOLDER_MISMATCH。两种顺序均 409 且不写数据。
        long pRejected = results.stream()
                .filter(r -> conflict(r, "LEASE_SUPERSEDED") || conflict(r, "LEASE_HOLDER_MISMATCH"))
                .count();
        assertThat(qOk).isEqualTo(1);
        assertThat(pRejected).isEqualTo(1);

        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(task.resultAgentId()).isEqualTo("Q");
        assertThat(task.resultToken()).isEqualTo(2);
        assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
        assertThat(releaseService.findOrder(releaseId).roundSuccess()).isEqualTo(1);
        assertThat(releaseService.findOrder(releaseId).roundFailed()).isZero();
    }

    @Test
    void 同一令牌并发回执至多终结一次_只有首次计样本() throws Exception {
        String deviceId = "d4";
        deviceService.register(new RegisterDeviceRequest("s4-d", deviceId, "m1", "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("s4-r", "m1", "1.0.0", "2.0.0", 100)).releaseId();
        long taskId = taskService.pull(deviceId, "s4-p").task().taskId();
        taskService.acquire(taskId, new LeaseRequest("s4-a-p", "P"));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.receipt(taskId,
                    new ReceiptRequest("s4-rc-" + seq, ReceiptResult.SUCCESS, "P", 1)));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).as("持有人同结果重复回执均成功（重放），无异常").isNotInstanceOf(Exception.class);
        }
        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(task.resultAgentId()).isEqualTo("P");
        assertThat(task.resultToken()).isEqualTo(1);
        assertThat(releaseService.findOrder(releaseId).roundSuccess()).isEqualTo(1);
        assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
    }

    @Test
    void 到期接管与取消并发_提交顺序决定唯一合法结果() throws Exception {
        int cancelWins = 0;
        int takeoverWins = 0;
        for (int round = 0; round < 8; round++) {
            String deviceId = "d" + round;
            String prefix = "s5-" + round;
            deviceService.register(new RegisterDeviceRequest(prefix + "-d", deviceId, "m" + round, "1.0.0", 1));
            long releaseId = releaseService.create(new CreateReleaseRequest(
                    prefix + "-r", "m" + round, "1.0.0", "2.0.0", 100)).releaseId();
            long taskId = taskService.pull(deviceId, prefix + "-p").task().taskId();
            taskService.acquire(taskId, new LeaseRequest(prefix + "-a-p", "P"));
            clock.advance(Duration.ofSeconds(30));
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.acquire(taskId, new LeaseRequest("s5-q-" + seq, "Q")),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "s5-c-" + seq)));

            Object acquireResult = results.get(0);
            var task = taskRepository.findById(taskId).orElseThrow();
            // 无论顺序如何，取消最终都会把未终结任务置为 CANCELLED；用领取结果判断提交顺序
            assertThat(task.status()).isEqualTo(TaskStatus.CANCELLED);
            if (acquireResult instanceof com.example.starter.firmware.api.LeaseView lv) {
                takeoverWins++;
                assertThat(lv.agentId()).isEqualTo("Q");
                assertThat(lv.token()).isEqualTo(2);
            } else {
                cancelWins++;
                assertThat(acquireResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ae.code()).isEqualTo("TASK_CANCELLED");
                        });
            }
            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.CANCELLED);
            assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("1.0.0");
        }
        assertThat(cancelWins + takeoverWins).isEqualTo(8);
    }

    @Test
    void 同requestId并发领取_重放首次快照且不重复递增令牌() throws Exception {
        long taskId = setupTask("d6", "s6");
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.acquire(taskId, new LeaseRequest("s6-same", "P")));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isInstanceOfSatisfying(com.example.starter.firmware.api.LeaseView.class,
                    lv -> {
                        assertThat(lv.agentId()).isEqualTo("P");
                        assertThat(lv.token()).isEqualTo(1);
                        assertThat(lv.expiresAtUtc()).isEqualTo("2026-10-08T00:00:30Z");
                    });
        }
        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.leaseAgentId()).isEqualTo("P");
        assertThat(task.leaseToken()).isEqualTo(1);
        Long idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?", Long.class, "s6-same");
        assertThat(idemCount).isEqualTo(1);
    }

    @Test
    void 续租与取消并发_按提交顺序产生唯一合法结果_取消最终生效() throws Exception {
        int renewFirst = 0;
        int cancelFirst = 0;
        for (int round = 0; round < 8; round++) {
            String deviceId = "e" + round;
            String model = "n" + round;
            String prefix = "s7-" + round;
            clock.setInstant(ClockTestConfiguration.START);
            deviceService.register(new RegisterDeviceRequest(prefix + "-d", deviceId, model, "1.0.0", 1));
            long releaseId = releaseService.create(new CreateReleaseRequest(
                    prefix + "-r", model, "1.0.0", "2.0.0", 100)).releaseId();
            long taskId = taskService.pull(deviceId, prefix + "-p").task().taskId();
            taskService.acquire(taskId, new LeaseRequest(prefix + "-a-p", "P"));
            clock.setInstant(ClockTestConfiguration.START.plusSeconds(10));
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.renew(taskId,
                            new RenewLeaseRequest("s7-ren-" + seq, "P", 1)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "s7-c-" + seq)));

            Object renewResult = results.get(0);
            assertThat(results.get(1)).as("取消对既有发布单始终成功").isNotInstanceOf(Exception.class);
            var task = taskRepository.findById(taskId).orElseThrow();
            assertThat(task.status()).isEqualTo(TaskStatus.CANCELLED);
            if (renewResult instanceof com.example.starter.firmware.api.LeaseView) {
                renewFirst++;
            } else {
                cancelFirst++;
                assertThat(renewResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ae.code()).isEqualTo("TASK_CANCELLED");
                        });
            }
            // 取消后设备版本不变，租约操作即便先提交也不会写回结果
            assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("1.0.0");
        }
        assertThat(renewFirst + cancelFirst).isEqualTo(8);
    }
}
