package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateInstallTaskRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.StopConfirmRequest;
import com.example.starter.firmware.api.TaskCancelRequest;
import com.example.starter.firmware.api.TaskStartRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
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
 * 安装握手并发测试：真实并发打到 H2 事务与唯一索引上，
 * 验证设备独占安装槽、取消代次与停止确认的串行裁决。
 */
@SpringBootTest
class FirmwareInstallConcurrencyTest {

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

    private long newInstallTask(String model, String deviceId, String requestId) {
        deviceService.register(new RegisterDeviceRequest(requestId + "-d", deviceId, model, "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest(requestId + "-r", model, "1.0.0", "2.0.0", 100)).releaseId();
        long taskId = taskService.createInstallTask(releaseId,
                new CreateInstallTaskRequest(requestId + "-c", "op", deviceId, 1)).taskId();
        taskService.start(taskId, new TaskStartRequest(requestId + "-s", "op", 1));
        return taskId;
    }

    @Test
    void 并发停止确认与开始_槽不重叠且结局唯一() throws Exception {
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            long t1 = newInstallTask(model, "d" + round, "r" + round + "-a");
            long releaseId = taskRepository.findById(t1).orElseThrow().releaseId();
            releaseService.cancel(releaseId, "r" + round + "-x");
            assertThat(taskRepository.findById(t1).orElseThrow().status())
                    .isEqualTo(TaskStatus.CANCEL_REQUESTED);
            long v2 = releaseService.create(
                    new CreateReleaseRequest("r" + round + "-b", model, "1.0.0", "3.0.0", 100)).releaseId();
            long t2 = taskService.createInstallTask(v2,
                    new CreateInstallTaskRequest("r" + round + "-c2", "op", "d" + round, 1)).taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.stopConfirm(t1,
                            new StopConfirmRequest("r" + seq + "-sc", "op", 1, 1)),
                    (Callable<Object>) () -> taskService.start(t2,
                            new TaskStartRequest("r" + seq + "-st", "op", 1))));

            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));
            assertThat(taskRepository.findById(t1).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
            Long slotHolders = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE device_slot = ?", Long.class, "d" + round);
            TaskStatus t2Status = taskRepository.findById(t2).orElseThrow().status();
            if (results.get(1) instanceof ApiException ae) {
                // 开始先看到旧槽未释放：422，槽随后由停止确认释放
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(t2Status).isEqualTo(TaskStatus.PENDING);
                assertThat(slotHolders).isZero();
            } else {
                // 停止确认先提交：新任务占槽成功，全库至多一个占用者
                assertThat(t2Status).isEqualTo(TaskStatus.INSTALLING);
                assertThat(slotHolders).isEqualTo(1);
            }
        }
    }

    @Test
    void 并发成功回执与取消请求_唯一结局且不重复计数() throws Exception {
        int successWins = 0;
        int cancelWins = 0;
        for (int round = 0; round < 10; round++) {
            long taskId = newInstallTask("m" + round, "d" + round, "r" + round);
            long releaseId = taskRepository.findById(taskId).orElseThrow().releaseId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("r" + seq + "-ok", ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> taskService.cancelRequest(taskId,
                            new TaskCancelRequest("r" + seq + "-cx", "op", 1))));

            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));
            var task = taskRepository.findById(taskId).orElseThrow();
            var order = releaseService.findOrder(releaseId);
            if (task.status() == TaskStatus.SUCCESS) {
                successWins++;
                assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(deviceService.get("d" + round).currentVersion()).isEqualTo("2.0.0");
                assertThat(task.deviceSlot()).isNull();
                assertThat(order.roundSuccess()).isEqualTo(1);
            } else {
                cancelWins++;
                assertThat(task.status()).isEqualTo(TaskStatus.CANCEL_REQUESTED);
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(deviceService.get("d" + round).currentVersion()).isEqualTo("1.0.0");
                assertThat(task.deviceSlot()).isEqualTo("d" + round);
                assertThat(order.roundSuccess()).isZero();
            }
        }
        assertThat(successWins + cancelWins).isEqualTo(10);
    }

    @Test
    void 并发停止确认_同代次只成功一次() throws Exception {
        long taskId = newInstallTask("m1", "d1", "r1");
        taskService.cancelRequest(taskId, new TaskCancelRequest("r2", "op", 1));
        assertThat(taskRepository.findById(taskId).orElseThrow().cancelGeneration()).isEqualTo(1);

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.stopConfirm(taskId,
                    new StopConfirmRequest("rc-" + seq, "op", 1, 1)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.deviceSlot()).isNull();
    }

    @Test
    void 并发取消请求_代次串行递增且槽保持() throws Exception {
        long taskId = newInstallTask("m1", "d1", "r1");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.cancelRequest(taskId,
                    new TaskCancelRequest("rc-" + seq, "op", 1)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.status()).isEqualTo(TaskStatus.CANCEL_REQUESTED);
        assertThat(task.cancelGeneration()).isEqualTo(threads);
        assertThat(task.deviceSlot()).isEqualTo("d1");
        // 仅最新代次可确认停止
        taskService.stopConfirm(taskId, new StopConfirmRequest("rc-final", "op", 1, threads));
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
    }
}
