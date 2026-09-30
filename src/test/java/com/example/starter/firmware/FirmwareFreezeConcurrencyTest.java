package com.example.starter.firmware;

import com.example.starter.firmware.api.ApproveFreezeResumeRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.FreezeVersionRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ResumeFreezeRequest;
import com.example.starter.firmware.domain.FreezeStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.FreezeEventRepository;
import com.example.starter.firmware.repo.FreezeRecordRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.FreezeService;
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
 * 版本冻结并发与幂等边界测试：真实并发打到 H2 事务、行锁与唯一约束上，
 * 验证冻结/恢复与任务创建按提交顺序决胜，失败不改冻结状态。
 */
@SpringBootTest
class FirmwareFreezeConcurrencyTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private FreezeService freezeService;
    @Autowired
    private FreezeRecordRepository freezeRecordRepository;
    @Autowired
    private FreezeEventRepository freezeEventRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_freeze_event");
        jdbc.update("DELETE FROM release_freeze_record");
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

    @Test
    void 并发冻结与拉取_按提交顺序唯一决胜_冻结后不再建任务() throws Exception {
        int pullWins = 0;
        int freezeWins = 0;
        for (int round = 0; round < 12; round++) {
            String model = "m" + round;
            String version = "2.0." + round;
            String deviceId = "d" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 5));
            releaseService.create(new CreateReleaseRequest("req-r" + round, model, "1.0.0", version, 100));
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq),
                    (Callable<Object>) () -> freezeService.freeze(
                            new FreezeVersionRequest("req-f" + seq, version, "alice", "紧急冻结"))));

            Object pullResult = results.get(0);
            Object freezeResult = results.get(1);
            assertThat(freezeResult).as("冻结最终必须成功提交").isNotInstanceOf(Exception.class);
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE device_id = ?", Long.class, deviceId);

            if (pullResult instanceof PullResponse response && response.task() != null) {
                // 拉取先提交：任务已建且占住；冻结随后提交
                pullWins++;
                assertThat(taskCount).isEqualTo(1);
                assertThat(freezeService.history(version).current()).isNotNull();
            } else {
                // 冻结先提交：拉取 409 VERSION_FROZEN，任务不落库、不占容量
                freezeWins++;
                assertThat(pullResult).isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("VERSION_FROZEN");
                });
                assertThat(taskCount).isZero();
            }
            // 冻结提交完成后，任何新拉取都必须 409
            String otherDevice = "e" + round;
            deviceService.register(new RegisterDeviceRequest("req-e" + round, otherDevice, model, "1.0.0", 6));
            try {
                taskService.pull(otherDevice, "req-p2-" + round);
                throw new AssertionError("冻结生效后新设备拉取应 409");
            } catch (ApiException e) {
                assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.code()).isEqualTo("VERSION_FROZEN");
            }
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE device_id = ?", Long.class, otherDevice)).isZero();
        }
        assertThat(pullWins + freezeWins).isEqualTo(12);
        // 两种提交顺序都必须真实出现，证明竞争双方都被覆盖而非恒定偏向一侧
        assertThat(pullWins).as("拉取先提交而建成任务的轮次").isPositive();
        assertThat(freezeWins).as("冻结先提交而拉取409的轮次").isPositive();
    }

    @Test
    void 并发审批与拉取_按提交顺序决胜_审批事件只落一条() throws Exception {
        int pullWins = 0;
        int approveWins = 0;
        for (int round = 0; round < 12; round++) {
            String model = "mm" + round;
            String version = "3.0." + round;
            String deviceId = "dd" + round;
            deviceService.register(new RegisterDeviceRequest("rd" + round, deviceId, model, "1.0.0", 5));
            releaseService.create(new CreateReleaseRequest("rr" + round, model, "1.0.0", version, 100));
            long freezeId = freezeService.freeze(
                    new FreezeVersionRequest("rf" + round, version, "alice", "冻结")).freezeId();
            freezeService.requestResume(freezeId,
                    new ResumeFreezeRequest("rrq" + round, "alice", "已修复"));
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "rp" + seq),
                    (Callable<Object>) () -> freezeService.approveResume(freezeId,
                            new ApproveFreezeResumeRequest("ra" + seq, "bob"))));

            Object pullResult = results.get(0);
            assertThat(results.get(1)).as("异于人审批必须成功").isNotInstanceOf(Exception.class);
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE device_id = ?", Long.class, deviceId);

            if (pullResult instanceof PullResponse response && response.task() != null) {
                // 审批先提交：冻结解除，任务建成
                pullWins++;
                assertThat(taskCount).isEqualTo(1);
            } else {
                // 拉取先进入：看到冻结仍生效，409 不建任务；审批随后提交
                approveWins++;
                assertThat(pullResult).isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("VERSION_FROZEN");
                });
                assertThat(taskCount).isZero();
            }
            // 最终冻结已恢复，审批事件恰好一条，冻结区间保留
            assertThat(freezeRecordRepository.findById(freezeId).orElseThrow().status())
                    .isEqualTo(FreezeStatus.RESUMED);
            Long approvedEvents = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_freeze_event WHERE freeze_id = ? AND event_type = 'RESUME_APPROVED'",
                    Long.class, freezeId);
            assertThat(approvedEvents).isEqualTo(1);
        }
        assertThat(pullWins + approveWins).isEqualTo(12);
        // 两种提交顺序都必须真实出现
        assertThat(pullWins).as("审批先提交而建成任务的轮次").isPositive();
        assertThat(approveWins).as("拉取先看到冻结而409的轮次").isPositive();
    }

    @Test
    void 并发同requestId冻结_只生成一条生效冻结_其余重放快照() throws Exception {
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> freezeService.freeze(
                    new FreezeVersionRequest("req-same-freeze", "6.0.0", "alice", "同一请求")));
        }
        List<Object> results = runConcurrently(tasks);

        Long firstId = (Long) jdbc.queryForObject(
                "SELECT id FROM release_freeze_record WHERE version = '6.0.0'", Long.class);
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
            assertThat(result).hasFieldOrPropertyWithValue("freezeId", firstId)
                    .hasFieldOrPropertyWithValue("status", "FROZEN");
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_freeze_record WHERE version = '6.0.0'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_freeze_event WHERE version = '6.0.0'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void 并发同requestId审批_只恢复一次_审计只一条() throws Exception {
        deviceService.register(new RegisterDeviceRequest("rd1", "d1", "m1", "1.0.0", 1));
        releaseService.create(new CreateReleaseRequest("rr1", "m1", "1.0.0", "7.0.0", 100));
        long freezeId = freezeService.freeze(
                new FreezeVersionRequest("rf1", "7.0.0", "alice", "冻结")).freezeId();
        freezeService.requestResume(freezeId, new ResumeFreezeRequest("rrq1", "alice", "修复"));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> freezeService.approveResume(freezeId,
                    new ApproveFreezeResumeRequest("req-same-approve", "bob")));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
            assertThat(result).hasFieldOrPropertyWithValue("status", "RESUMED")
                    .hasFieldOrPropertyWithValue("approvedBy", "bob");
        }
        assertThat(freezeRecordRepository.findById(freezeId).orElseThrow().status())
                .isEqualTo(FreezeStatus.RESUMED);
        assertThat(freezeEventRepository.countByFreeze(freezeId)).isEqualTo(3);
        // active_version 已释放，同版本可再次冻结
        long second = freezeService.freeze(
                new FreezeVersionRequest("rf2", "7.0.0", "carol", "新问题")).freezeId();
        assertThat(second).isNotEqualTo(freezeId);
    }

    @Test
    void 并发同人审批_全部422_冻结状态与原因时刻审计不变() throws Exception {
        long freezeId = freezeService.freeze(
                new FreezeVersionRequest("rf1", "8.0.0", "alice", "原始冻结原因")).freezeId();
        freezeService.requestResume(freezeId, new ResumeFreezeRequest("rrq1", "alice", "申请恢复"));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> freezeService.approveResume(freezeId,
                    new ApproveFreezeResumeRequest("r-self-" + seq, "alice")));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOfSatisfying(ApiException.class, ae -> {
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
            });
        }
        var record = freezeRecordRepository.findById(freezeId).orElseThrow();
        assertThat(record.status()).isEqualTo(FreezeStatus.FROZEN);
        assertThat(record.createdBy()).isEqualTo("alice");
        assertThat(record.reason()).isEqualTo("原始冻结原因");
        assertThat(record.frozenAtUtc()).isNotBlank();
        assertThat(record.resumeRequestedBy()).isEqualTo("alice");
        assertThat(record.approvedBy()).isNull();
        assertThat(record.resumedAtUtc()).isNull();
        // 只有冻结与申请两条事件，无任何审批事件
        assertThat(freezeEventRepository.countByFreeze(freezeId)).isEqualTo(2);
        // 失败不占键：其中一个 requestId 换人后可审批成功
        assertThat(freezeService.approveResume(freezeId,
                new ApproveFreezeResumeRequest("r-self-0", "bob")).status()).isEqualTo("RESUMED");
    }
}
