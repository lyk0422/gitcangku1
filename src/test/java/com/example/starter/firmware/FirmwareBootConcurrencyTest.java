package com.example.starter.firmware;

import com.example.starter.firmware.api.BootReceiptRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.BootReceiptPhase;
import com.example.starter.firmware.domain.BootTaskStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.BootRollbackRecordRepository;
import com.example.starter.firmware.repo.BootTaskRepository;
import com.example.starter.firmware.service.BootTaskService;
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
 * 双分区试启动并发测试：健康确认/失败/到期结算竞争只产生一个终态，失败率统计只计一次，
 * 回滚只记录一次，回滚后并发重试只产生一个新任务ID（真实并发打到 H2 事务与行锁上）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class FirmwareBootConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private BootTaskService bootTaskService;

    @Autowired
    private BootTaskRepository bootTaskRepository;

    @Autowired
    private BootRollbackRecordRepository rollbackRecordRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM boot_rollback_record");
        jdbc.update("DELETE FROM boot_task");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.set(TestClockConfig.T0);
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

    private long newDualReleaseWithDevice(String tag) {
        deviceService.register(new RegisterDeviceRequest("req-d-" + tag, "d-" + tag, "m-" + tag,
                "v1", 1));
        return releaseService.create(new CreateReleaseRequest("req-r-" + tag, "m-" + tag, "v1", "v2",
                100, 2, 100, true)).releaseId();
    }

    private long pull(String deviceId, String requestId) {
        PullResponse response = taskService.pull(deviceId, requestId);
        assertThat(response.bootTask()).isNotNull();
        return response.bootTask().taskId();
    }

    private void trial(long taskId, String requestId) {
        bootTaskService.receipt(taskId, new BootReceiptRequest(requestId, ReceiptResult.SUCCESS,
                BootReceiptPhase.TRIAL_BOOT, "B", "v2"));
    }

    @Test
    void 并发健康确认与失败_只产生一个终态_统计只计一次() throws Exception {
        int committed = 0;
        int rolledBack = 0;
        for (int round = 0; round < 6; round++) {
            long releaseId = newDualReleaseWithDevice("t1-" + round);
            long taskId = pull("d-t1-" + round, "req-p-" + round);
            trial(taskId, "req-t-" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> bootTaskService.receipt(taskId,
                            new BootReceiptRequest("req-h-" + seq, ReceiptResult.SUCCESS,
                                    BootReceiptPhase.HEALTH_CONFIRM, null, null)),
                    (Callable<Object>) () -> bootTaskService.receipt(taskId,
                            new BootReceiptRequest("req-f-" + seq, ReceiptResult.FAILED,
                                    null, null, null))));

            long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
            long conflicts = results.stream()
                    .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                    .count();
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);

            var task = bootTaskRepository.findById(taskId).orElseThrow();
            var order = releaseService.findOrder(releaseId);
            var device = deviceService.get("d-t1-" + round);
            if (task.status() == BootTaskStatus.COMMITTED) {
                committed++;
                assertThat(device.currentVersion()).isEqualTo("v2");
                assertThat(device.activePartition()).isEqualTo("B");
                assertThat(order.roundSuccess()).isEqualTo(1);
                assertThat(order.roundFailed()).isZero();
                assertThat(rollbackRecordRepository.countByTask(taskId)).isZero();
            } else {
                rolledBack++;
                assertThat(task.status()).isEqualTo(BootTaskStatus.ROLLED_BACK);
                assertThat(device.currentVersion()).isEqualTo("v1");
                assertThat(device.activePartition()).isEqualTo("A");
                assertThat(order.roundSuccess()).isZero();
                assertThat(order.roundFailed()).isEqualTo(1);
                assertThat(rollbackRecordRepository.countByTask(taskId)).isEqualTo(1);
            }
        }
        assertThat(committed + rolledBack).isEqualTo(6);
    }

    @Test
    void 并发到期结算与健康确认_回滚只记录一次() throws Exception {
        long releaseId = newDualReleaseWithDevice("t2");
        long taskId = pull("d-t2", "req-p");
        trial(taskId, "req-t");
        clock.advance(Duration.ofSeconds(60));

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> bootTaskService.receipt(taskId,
                        new BootReceiptRequest("req-h", ReceiptResult.SUCCESS,
                                BootReceiptPhase.HEALTH_CONFIRM, null, null)),
                (Callable<Object>) () -> bootTaskService.listByRelease(releaseId, null),
                (Callable<Object>) () -> bootTaskService.listByRelease(releaseId, null)));

        assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(results.get(1)).isNotInstanceOf(Exception.class);
        assertThat(results.get(2)).isNotInstanceOf(Exception.class);

        var task = bootTaskRepository.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(BootTaskStatus.ROLLED_BACK);
        assertThat(rollbackRecordRepository.countByTask(taskId)).isEqualTo(1);
        var order = releaseService.findOrder(releaseId);
        assertThat(order.roundFailed()).isEqualTo(1);
        assertThat(order.roundSuccess()).isZero();
        assertThat(deviceService.get("d-t2").currentVersion()).isEqualTo("v1");
    }

    @Test
    void 并发回滚后重试拉取_只产生一个新任务ID() throws Exception {
        long releaseId = newDualReleaseWithDevice("t3");
        long task1 = pull("d-t3", "req-p1");
        bootTaskService.receipt(task1, new BootReceiptRequest("req-f1", ReceiptResult.FAILED,
                null, null, null));
        assertThat(bootTaskRepository.findById(task1).orElseThrow().status())
                .isEqualTo(BootTaskStatus.ROLLED_BACK);

        int threads = 8;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d-t3", "req-retry-" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.bootTask()).isNotNull();
            assertThat(response.bootTask().status()).isEqualTo("PENDING");
            taskIds.add(response.bootTask().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(taskIds.get(0)).isNotEqualTo(task1);
        assertThat(bootTaskRepository.countByReleaseAndDevice(releaseId, "d-t3")).isEqualTo(2);
    }

    @Test
    void 并发同requestId试启动回执_重放一致且只进入TRIAL一次() throws Exception {
        newDualReleaseWithDevice("t4");
        long taskId = pull("d-t4", "req-p");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> bootTaskService.receipt(taskId, new BootReceiptRequest("req-same",
                    ReceiptResult.SUCCESS, BootReceiptPhase.TRIAL_BOOT, "B", "v2")));
        }
        List<Object> results = runConcurrently(tasks);

        Object first = results.get(0);
        assertThat(first).isNotInstanceOf(Exception.class);
        for (Object result : results) {
            assertThat(result).isEqualTo(first);
        }
        var task = bootTaskRepository.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(BootTaskStatus.TRIAL);
        assertThat(task.targetPartition()).isEqualTo("B");
        assertThat(deviceService.get("d-t4").currentVersion()).isEqualTo("v1");
    }
}
