package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RegisterFirmwareRequest;
import com.example.starter.firmware.api.RegisterCertChainRequest;import com.example.starter.firmware.api.RevokeCertRequest;
import com.example.starter.firmware.api.RolloverCertRequest;
import com.example.starter.firmware.domain.CertChainStatus;
import com.example.starter.firmware.domain.FirmwareCertChain;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CertChainRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.CertService;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.DeviceTrustService;
import com.example.starter.firmware.service.MutableClock;
import com.example.starter.firmware.service.FirmwareService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.ReviewService;
import com.example.starter.firmware.service.TaskService;
import com.example.starter.firmware.api.DeviceTrustRequest;
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
 * 证书轮换/撤销并发与幂等真实 H2 测试：轮换按链行锁提交顺序串行、同 requestId 重放只生效一次、
 * 撤销与回执按提交顺序产生唯一合法裁决。
 */
@SpringBootTest
class FirmwareCertConcurrencyTest {

    @Autowired
    private CertService certService;
    @Autowired
    private FirmwareService firmwareService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private DeviceTrustService deviceTrustService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private CertChainRepository certChainRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM receipt_review");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM cert_chain_device");
        jdbc.update("DELETE FROM firmware_cert_chain");
        jdbc.update("DELETE FROM firmware_compatibility");
        jdbc.update("DELETE FROM firmware_version");
        jdbc.update("DELETE FROM device_trust");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.setInstant(Instant.parse("2026-10-02T00:00:00Z"));
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private void seedFirmwareWithOpenChain(String model, String deviceId, int bucket) {
        firmwareService.register(new RegisterFirmwareRequest(
                "fw-" + model, model, "2.0.0", "oss://fw/" + model + "/2.0.0.bin"));
        certService.registerChain(new RegisterCertChainRequest("cert-" + model, model, "2.0.0",
                "CN=sign-v1", "CN=root", "fp-v1",
                "2026-10-01T00:00:00Z", null, List.of(), "ops"));
        deviceService.register(new RegisterDeviceRequest("dev-" + model + "-" + deviceId,
                deviceId, model, "1.0.0", bucket));
        deviceTrustService.put(deviceId, new DeviceTrustRequest(
                "trust-" + model + "-" + deviceId, "anchor-" + deviceId, true, "ops"));
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
    void 并发轮换同一旧链_恰好一次成功_区间相接且任意时刻唯一当前链() throws Exception {
        firmwareService.register(new RegisterFirmwareRequest(
                "fw1", "m1", "2.0.0", "oss://fw/m1/2.0.0.bin"));
        certService.registerChain(new RegisterCertChainRequest("c1", "m1", "2.0.0",
                "CN=s1", "CN=r1", "fp1", "2026-10-01T00:00:00Z", null, List.of(), "ops"));
        long firmwareId = jdbc.queryForObject(
                "SELECT id FROM firmware_version WHERE model='m1' AND version='2.0.0'", Long.class);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int seq = i;
            tasks.add(() -> certService.rollover(new RolloverCertRequest(
                    "roll-" + seq, "m1", "2.0.0", 1, 2,
                    "2026-11-01T00:00:00Z", "CN=s2", "CN=r2", "fp2", List.of(), "ops")));
        }
        List<Object> results = runConcurrently(tasks);

        long success = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(success).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        List<FirmwareCertChain> chains = certChainRepository.findByFirmware(firmwareId);
        assertThat(chains).hasSize(2);
        FirmwareCertChain old = chains.get(0);
        FirmwareCertChain fresh = chains.get(1);
        assertThat(old.chainVersion()).isEqualTo(1);
        assertThat(fresh.chainVersion()).isEqualTo(2);
        // 半开区间端点相接、不重叠
        assertThat(old.validTo()).isEqualTo(fresh.validFrom());
        // 交接前唯旧、交接点起唯新，不存在同时刻两条有效链
        assertThat(certService.currentForFirmware(firmwareId,
                Instant.parse("2026-10-31T23:59:59Z")).orElseThrow().chainVersion()).isEqualTo(1);
        assertThat(certService.currentForFirmware(firmwareId,
                Instant.parse("2026-11-01T00:00:00Z")).orElseThrow().chainVersion()).isEqualTo(2);
    }

    @Test
    void 并发同requestId轮换_重放一致且只新增一条链() throws Exception {
        firmwareService.register(new RegisterFirmwareRequest(
                "fw1", "m1", "2.0.0", "oss://fw/m1/2.0.0.bin"));
        certService.registerChain(new RegisterCertChainRequest("c1", "m1", "2.0.0",
                "CN=s1", "CN=r1", "fp1", "2026-10-01T00:00:00Z", null, List.of(), "ops"));
        long firmwareId = jdbc.queryForObject("SELECT id FROM firmware_version WHERE model='m1'", Long.class);

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> certService.rollover(new RolloverCertRequest(
                    "roll-same", "m1", "2.0.0", 1, 2,
                    "2026-11-01T00:00:00Z", "CN=s2", "CN=r2", "fp2", List.of(), "ops")));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        assertThat(certChainRepository.findByFirmware(firmwareId)).hasSize(2);
    }

    @Test
    void 并发撤销与回执_结果始终为两种合法裁决之一且无半成品() throws Exception {
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            String deviceId = "d" + round;
            seedFirmwareWithOpenChain(model, deviceId, round % 50);
            long releaseId = releaseService.create(new CreateReleaseRequest(
                    "rel-" + round, model, "1.0.0", "2.0.0", 100)).releaseId();
            long taskId = taskService.pull(deviceId, "pull-" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> certService.revoke(new RevokeCertRequest(
                            "rev-" + seq, model, "2.0.0", 1, "secops")),
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("rc-" + seq, ReceiptResult.SUCCESS))));

            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));
            TaskStatus taskStatus = taskRepository.findById(taskId).orElseThrow().status();
            String deviceVersion = deviceService.get(deviceId).currentVersion();
            boolean reviewExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM receipt_review WHERE task_id = ?", Long.class, taskId) > 0;
            long firmwareId = jdbc.queryForObject(
                    "SELECT id FROM firmware_version WHERE model=?", Long.class, model);
            FirmwareCertChain chain = certChainRepository.findByFirmware(firmwareId).get(0);
            assertThat(chain.status()).isEqualTo(CertChainStatus.REVOKED);
            assertThat(taskStatus).isEqualTo(TaskStatus.SUCCESS);

            // 两种合法裁决之一：撤销先提交→复核单存在、版本停留；回执先提交→无复核单、版本已更新
            if (reviewExists) {
                assertThat(deviceVersion).isEqualTo("1.0.0");
            } else {
                assertThat(deviceVersion).isEqualTo("2.0.0");
            }
            assertThat(releaseService.findOrder(releaseId).id()).isEqualTo(releaseId);
        }
    }

    @Test
    void 撤销先提交_回执转复核不升版本_确认后才更新() {
        String model = "ma";
        String deviceId = "da";
        seedFirmwareWithOpenChain(model, deviceId, 1);
        long releaseId = releaseService.create(new CreateReleaseRequest(
                "rel-a", model, "1.0.0", "2.0.0", 100)).releaseId();
        long taskId = taskService.pull(deviceId, "pull-a").task().taskId();

        certService.revoke(new RevokeCertRequest("rev-a", model, "2.0.0", 1, "secops"));
        taskService.receipt(taskId, new ReceiptRequest("rc-a", ReceiptResult.SUCCESS));

        assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("1.0.0");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM receipt_review WHERE task_id = ?", String.class, taskId))
                .isEqualTo("PENDING_REVIEW");
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.SUCCESS);

        reviewService.confirm(taskId,
                new com.example.starter.firmware.api.ConfirmReviewRequest("ok-a", "manager"));
        assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
        assertThat(releaseService.findOrder(releaseId).status())
                .isEqualTo(com.example.starter.firmware.domain.ReleaseStatus.ACTIVE);
    }

    @Test
    void 回执先提交_版本已更新_随后撤销不产生复核单() {
        String model = "mb";
        String deviceId = "db";
        seedFirmwareWithOpenChain(model, deviceId, 1);
        long releaseId = releaseService.create(new CreateReleaseRequest(
                "rel-b", model, "1.0.0", "2.0.0", 100)).releaseId();
        long taskId = taskService.pull(deviceId, "pull-b").task().taskId();

        taskService.receipt(taskId, new ReceiptRequest("rc-b", ReceiptResult.SUCCESS));
        assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");

        certService.revoke(new RevokeCertRequest("rev-b", model, "2.0.0", 1, "secops"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM receipt_review WHERE task_id = ?", Long.class, taskId)).isZero();
        // 已更新版本不回滚
        assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
        assertThat(releaseService.findOrder(releaseId).id()).isEqualTo(releaseId);
    }
}
