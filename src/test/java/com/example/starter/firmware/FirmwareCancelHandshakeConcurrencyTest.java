package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.StopConfirmRequest;
import com.example.starter.firmware.api.TaskCancelRequest;
import com.example.starter.firmware.api.TaskStartRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.SlotRepository;
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
 * 取消握手并发测试：真实并发打到 H2 事务与 device_install_slot 主键上，
 * 验证开始/回执/取消请求/停止确认共同裁决出唯一终态或唯一停止路径，槽不重复释放。
 */
@SpringBootTest
class FirmwareCancelHandshakeConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM device_install_slot");
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

    private long setupInstalling(String deviceId, String model) {
        deviceService.register(new RegisterDeviceRequest("req-d-" + deviceId, deviceId, model, "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r-" + model, model, "1.0.0", "2.0.0", 100)).releaseId();
        long taskId = taskService.pull(deviceId, "req-p-" + deviceId).task().taskId();
        taskService.start(taskId, new TaskStartRequest("req-s-" + deviceId, "op1", 1));
        return taskId;
    }

    @Test
    void 并发开始同设备两个任务_槽互斥只有一个占槽() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId1 = releaseService.create(
                new CreateReleaseRequest("req-r1", "m1", "1.0.0", "2.0.0", 100)).releaseId();
        long t1 = taskService.pull("d1", "req-p1").task().taskId();
        taskService.start(t1, new TaskStartRequest("req-s1", "op1", 1));
        // 取消发布：t1 转 CANCEL_REQUESTED 仍占槽；新发布单产生同设备新任务 t2
        releaseService.cancel(releaseId1, "req-x1");
        long releaseId2 = releaseService.create(
                new CreateReleaseRequest("req-r2", "m1", "1.0.0", "3.0.0", 100)).releaseId();
        long t2 = taskService.pull("d1", "req-p2").task().taskId();

        // 并发：停止确认 t1 与开始 t2，槽主键保证互斥
        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.confirmStop(t1,
                        new StopConfirmRequest("req-k1", "op1", 1, 1)),
                (Callable<Object>) () -> taskService.start(t2,
                        new TaskStartRequest("req-s2", "op2", 1))));
        assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

        // 若开始因槽未释放被拒（422），确认已提交后重试必须成功
        if (results.get(1) instanceof ApiException ae) {
            assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(ae.code()).isEqualTo("DEVICE_SLOT_BUSY");
            taskService.start(t2, new TaskStartRequest("req-s2b", "op2", 1));
        }
        assertThat(taskRepository.findById(t1).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(taskRepository.findById(t2).orElseThrow().status()).isEqualTo(TaskStatus.INSTALLING);
        assertThat(slotRepository.findHolder("d1")).contains(t2);
        Long slots = jdbc.queryForObject("SELECT COUNT(*) FROM device_install_slot", Long.class);
        assertThat(slots).isEqualTo(1);
        assertThat(releaseId2).isPositive();
    }

    @Test
    void 并发回执与任务取消_唯一终态或唯一停止路径() throws Exception {
        int receiptWins = 0;
        int cancelWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            long taskId = setupInstalling(deviceId, model);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-rc-" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> taskService.cancelTask(taskId,
                            new TaskCancelRequest("req-c-" + seq, "op1", 1))));
            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            if (finalStatus == TaskStatus.SUCCESS) {
                // 回执先裁决：成功终态，取消请求 409，槽已释放，版本已更新
                receiptWins++;
                assertThat(results.get(0)).isNotInstanceOf(Exception.class);
                assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.code()).isEqualTo("TASK_ALREADY_FINISHED"));
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
                assertThat(slotRepository.findHolder(deviceId)).isEmpty();
            } else {
                // 取消先裁决：待确认仍占槽，回执 409；同代次停止确认后释放
                cancelWins++;
                assertThat(finalStatus).isEqualTo(TaskStatus.CANCEL_REQUESTED);
                assertThat(results.get(1)).isNotInstanceOf(Exception.class);
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.code()).isEqualTo("TASK_CANCEL_REQUESTED"));
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("1.0.0");
                assertThat(slotRepository.findHolder(deviceId)).contains(taskId);
                taskService.confirmStop(taskId, new StopConfirmRequest("req-k-" + seq, "op1", 1, 1));
                assertThat(taskRepository.findById(taskId).orElseThrow().status())
                        .isEqualTo(TaskStatus.CANCELLED);
                assertThat(slotRepository.findHolder(deviceId)).isEmpty();
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("1.0.0");
            }
        }
        assertThat(receiptWins + cancelWins).isEqualTo(10);
    }

    @Test
    void 并发停止确认_同代次只生效一次_槽只释放一次() throws Exception {
        long taskId = setupInstalling("d1", "m1");
        taskService.cancelTask(taskId, new TaskCancelRequest("req-c1", "op1", 1));
        assertThat(slotRepository.findHolder("d1")).contains(taskId);

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.confirmStop(taskId,
                    new StopConfirmRequest("req-k" + seq, "op1", 1, 1)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.CONFLICT
                        && ae.code().equals("TASK_NOT_CANCEL_REQUESTED"))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(slotRepository.findHolder("d1")).isEmpty();
    }

    @Test
    void 并发发布取消与开始_无安装中残留_槽状态一致() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 1));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, "1.0.0", "2.0.0", 100)).releaseId();
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.start(taskId,
                            new TaskStartRequest("req-s" + seq, "op1", 1)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-x" + seq)));
            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            if (results.get(0) instanceof ApiException ae) {
                // 取消先提交：PENDING 被直接取消，开始 409
                assertThat(ae.code()).isEqualTo("TASK_NOT_PENDING");
                assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
                assertThat(slotRepository.findHolder(deviceId)).isEmpty();
            } else {
                // 开始先提交：发布取消将其转 CANCEL_REQUESTED，仍占槽等待确认
                assertThat(finalStatus).isEqualTo(TaskStatus.CANCEL_REQUESTED);
                assertThat(slotRepository.findHolder(deviceId)).contains(taskId);
                taskService.confirmStop(taskId, new StopConfirmRequest("req-k" + seq, "op1", 1, 1));
                assertThat(slotRepository.findHolder(deviceId)).isEmpty();
            }
            assertThat(finalStatus).isNotIn(TaskStatus.PENDING, TaskStatus.INSTALLING);
        }
    }
}
