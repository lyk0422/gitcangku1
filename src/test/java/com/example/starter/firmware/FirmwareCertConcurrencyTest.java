package com.example.starter.firmware;

import com.example.starter.firmware.api.BatchStartRequest;
import com.example.starter.firmware.api.BatchStartResponse;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterCertChainRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RevokeCertChainRequest;
import com.example.starter.firmware.api.RotateCertChainRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReviewStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.CertService;
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

import java.time.Instant;
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
 * 证书轮换/撤销与启动、拉取、回执、确认的并发边界测试：
 * 真实并发打到 H2 行锁与唯一约束上，按提交顺序裁决，不留半成品状态。
 */
@SpringBootTest
class FirmwareCertConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private CertService certService;

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
        jdbc.update("DELETE FROM cert_chain");
        jdbc.update("DELETE FROM cert_rejection_record");
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

    private String hoursAgo(int hours) {
        return Instant.now().minusSeconds(hours * 3600L).toString();
    }

    private long prepareReleaseWithChain(String suffix, String firmwareVersion) {
        deviceService.register(new RegisterDeviceRequest("req-d" + suffix, "dev-" + suffix, "m" + suffix,
                "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r" + suffix, "m" + suffix, "1.0.0", firmwareVersion, 100))
                .releaseId();
        certService.register(firmwareVersion, new RegisterCertChainRequest("req-c" + suffix, "v1",
                hoursAgo(1), null, null, "op-1"));
        return releaseId;
    }

    @Test
    void 并发撤销与回执_按提交顺序裁决且状态一致() throws Exception {
        int reviewMarked = 0;
        int normalCompleted = 0;
        for (int round = 0; round < 10; round++) {
            String firmware = "9." + round + ".0";
            prepareReleaseWithChain(String.valueOf(round), firmware);
            long taskId = taskService.pull("dev-" + round, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-rc" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> certService.revoke(firmware, "v1",
                            new RevokeCertChainRequest("req-rv" + seq, "op-1"))));
            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

            RolloutTask task = taskRepository.findById(taskId).orElseThrow();
            String deviceVersion = deviceService.get("dev-" + round).currentVersion();
            if (task.reviewStatus() == ReviewStatus.CERTIFICATE_REVOKED_REVIEW) {
                // 撤销先提交：回执标记复核，设备版本不更新
                reviewMarked++;
                assertThat(deviceVersion).isEqualTo("1.0.0");
            } else {
                // 回执先提交：正常完成并更新版本
                normalCompleted++;
                assertThat(task.reviewStatus()).isEqualTo(ReviewStatus.NONE);
                assertThat(deviceVersion).isEqualTo(firmware);
            }
            assertThat(task.status().name()).isEqualTo("SUCCESS");
        }
        assertThat(reviewMarked + normalCompleted).isEqualTo(10);
    }

    @Test
    void 并发同certKey轮换_重放一致且新链只建一次() throws Exception {
        String firmware = "9.90.0";
        prepareReleaseWithChain("rot", firmware);
        String newFrom = Instant.now().plusSeconds(3600).toString();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> certService.rotate(firmware, new RotateCertChainRequest("cert-key-1",
                    "v1", "v2", newFrom, null, null, "op-1")));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        assertThat(certService.listChains(firmware).chains()).hasSize(2);
        Long v2Count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cert_chain WHERE firmware_version = ? AND chain_version = 'v2'",
                Long.class, firmware);
        assertThat(v2Count).isEqualTo(1);
    }

    @Test
    void 并发批量启动与撤销_要么全部建任务要么整体422无半成品() throws Exception {
        int started = 0;
        int rejected = 0;
        for (int round = 0; round < 10; round++) {
            String firmware = "9.5" + round + ".0";
            String suffix = "s" + round;
            deviceService.register(new RegisterDeviceRequest("req-d1" + suffix, "a-" + suffix, "m" + suffix,
                    "1.0.0", 1));
            deviceService.register(new RegisterDeviceRequest("req-d2" + suffix, "b-" + suffix, "m" + suffix,
                    "1.0.0", 2));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + suffix, "m" + suffix, "1.0.0", firmware, 100))
                    .releaseId();
            certService.register(firmware, new RegisterCertChainRequest("req-c" + suffix, "v1",
                    hoursAgo(1), null, null, "op-1"));
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.start(releaseId,
                            new BatchStartRequest("req-st" + seq, List.of("a-" + suffix, "b-" + suffix))),
                    (Callable<Object>) () -> certService.revoke(firmware, "v1",
                            new RevokeCertChainRequest("req-rv" + seq, "op-1"))));

            Object startResult = results.get(0);
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId);
            if (startResult instanceof ApiException ae) {
                // 撤销先提交：批量启动整体 422，一台设备任务都不创建
                rejected++;
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(taskCount).as("第%d轮不得残留半成品任务", seq).isZero();
            } else {
                // 启动先提交：两台设备任务全部创建且固化 v1
                started++;
                assertThat(startResult).isInstanceOf(BatchStartResponse.class);
                assertThat(taskCount).isEqualTo(2);
                Long frozen = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND cert_chain_version = 'v1'",
                        Long.class, releaseId);
                assertThat(frozen).isEqualTo(2);
            }
        }
        assertThat(started + rejected).isEqualTo(10);
    }

    @Test
    void 并发确认与重复回执_版本只更新一次() throws Exception {
        String firmware = "9.80.0";
        prepareReleaseWithChain("cf", firmware);
        long taskId = taskService.pull("dev-cf", "req-p1").task().taskId();
        certService.revoke(firmware, "v1", new RevokeCertChainRequest("req-rv1", "op-1"));
        taskService.receipt(taskId, new ReceiptRequest("req-rc1", ReceiptResult.SUCCESS));
        assertThat(taskRepository.findById(taskId).orElseThrow().reviewStatus())
                .isEqualTo(ReviewStatus.CERTIFICATE_REVOKED_REVIEW);

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.confirm(taskId,
                    new com.example.starter.firmware.api.ConfirmTaskRequest("req-cf" + seq, "op-9")));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        // 恰好一个确认生效，其余 409；设备版本最终为目标版本
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(taskRepository.findById(taskId).orElseThrow().reviewStatus())
                .isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(deviceService.get("dev-cf").currentVersion()).isEqualTo(firmware);
    }
}
