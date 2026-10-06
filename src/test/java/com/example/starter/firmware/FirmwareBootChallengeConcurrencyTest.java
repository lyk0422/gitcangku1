package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.MaintenanceRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.domain.TaskType;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 启动挑战并发边界测试：真实并发打到 H2 事务与行锁上，
 * 验证维护、领取、回执的串行裁决与原子提交。
 */
@SpringBootTest
class FirmwareBootChallengeConcurrencyTest {

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

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * 闩锁对齐后并发执行，收集成功结果与异常。
     */
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

    private long createChallengeRelease(String requestId, String model) {
        return releaseService.create(new CreateReleaseRequest(requestId, model, "1.0.0", "2.0.0", 100,
                null, null, TaskType.BOOT_CHALLENGE)).releaseId();
    }

    @Test
    void 并发维护与回执_串行裁决且维护到更高计数后旧回执不得安装() throws Exception {
        int receiptWins = 0;
        int maintenanceWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 1));
            deviceService.maintain(deviceId, new MaintenanceRequest("req-m0-" + round, "op", 1, 4L));
            long releaseId = createChallengeRelease("req-r" + round, model);
            TaskView task = taskService.pull(deviceId, "req-p" + round).task();
            assertThat(task.frozenBootCount()).isEqualTo(4);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> deviceService.maintain(deviceId,
                            new MaintenanceRequest("req-m1-" + seq, "op", 2, 10L)),
                    (Callable<Object>) () -> taskService.receipt(task.taskId(),
                            new ReceiptRequest("req-rc-" + seq, ReceiptResult.SUCCESS, "op",
                                    task.challenge(), 7L))));

            // 维护始终成功：计数不可递减，10 恒大于 4 与 7
            assertThat(results.get(0)).isNotInstanceOf(Exception.class);
            DeviceView device = deviceService.get(deviceId);
            assertThat(device.bootCount()).isEqualTo(10);
            assertThat(device.deviceVersion()).isEqualTo(3);
            TaskStatus finalStatus = taskRepository.findById(task.taskId()).orElseThrow().status();
            if (results.get(1) instanceof ApiException ae) {
                // 维护先提交：c=7 不大于实际计数 10，旧回执不得安装
                maintenanceWins++;
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("STALE_BOOT_COUNT");
                assertThat(finalStatus).isEqualTo(TaskStatus.PENDING);
                assertThat(device.currentVersion()).isEqualTo("1.0.0");
            } else {
                // 回执先提交：原子安装到 7 后维护推进到 10
                receiptWins++;
                assertThat(results.get(1)).isInstanceOf(TaskView.class);
                assertThat(finalStatus).isEqualTo(TaskStatus.SUCCESS);
                assertThat(device.currentVersion()).isEqualTo("2.0.0");
            }
            Long roundSuccess = jdbc.queryForObject(
                    "SELECT round_success FROM release_order WHERE id = ?", Long.class, releaseId);
            assertThat(roundSuccess).isEqualTo(finalStatus == TaskStatus.SUCCESS ? 1 : 0);
        }
        assertThat(receiptWins + maintenanceWins).isEqualTo(10);
    }

    @Test
    void 并发相同计数回执_样本与设备计数只生效一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId = createChallengeRelease("req-r", "m1");
        TaskView task = taskService.pull("d1", "req-p").task();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.receipt(task.taskId(),
                    new ReceiptRequest("req-rc-" + seq, ReceiptResult.SUCCESS, "op",
                            task.challenge(), 5L)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        // 任务只终结一次、样本只记一次、设备计数只写一次
        assertThat(taskRepository.findById(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.SUCCESS);
        DeviceView device = deviceService.get("d1");
        assertThat(device.bootCount()).isEqualTo(5);
        assertThat(device.currentVersion()).isEqualTo("2.0.0");
        Long roundSuccess = jdbc.queryForObject(
                "SELECT round_success FROM release_order WHERE id = ?", Long.class, releaseId);
        assertThat(roundSuccess).isEqualTo(1);
    }

    @Test
    void 并发领取_挑战任务唯一且冻结计数与挑战一致() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        deviceService.maintain("d1", new MaintenanceRequest("req-m", "op", 1, 4L));
        long releaseId = createChallengeRelease("req-r", "m1");

        int threads = 8;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-pull-" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        List<String> challenges = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.task()).isNotNull();
            taskIds.add(response.task().taskId());
            challenges.add(response.task().challenge());
            assertThat(response.task().frozenBootCount()).isEqualTo(4);
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(challenges).allMatch(c -> c.equals(challenges.get(0)));
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(1);
    }
}
