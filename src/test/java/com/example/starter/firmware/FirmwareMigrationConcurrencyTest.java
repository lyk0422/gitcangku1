package com.example.starter.firmware;

import com.example.starter.firmware.api.AssignDeviceRequest;
import com.example.starter.firmware.api.CommandReceiptRequest;
import com.example.starter.firmware.api.CreateCohortRequest;
import com.example.starter.firmware.api.CreateRegionRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.MigrationActivateResponse;
import com.example.starter.firmware.api.MigrationItemInput;
import com.example.starter.firmware.domain.CohortAssignment;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.AssignmentRepository;
import com.example.starter.firmware.repo.CohortRepository;
import com.example.starter.firmware.service.CohortService;
import com.example.starter.firmware.service.MigrationService;
import com.example.starter.firmware.service.ReleaseService;
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
 * 迁移并发边界 H2 测试：两个迁移并发、迁移与旧代次回执并发。
 * 所有变更按活动行锁的提交顺序串行，设备归属、队列统计与指令状态必须来自同一结果，不产生部分迁移。
 */
@SpringBootTest
class FirmwareMigrationConcurrencyTest {

    @Autowired
    private MigrationService migrationService;
    @Autowired
    private CohortService cohortService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private CohortRepository cohortRepository;
    @Autowired
    private AssignmentRepository assignmentRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;
    private long releaseId;
    private long cohortA;
    private long cohortB;
    private long cohortC;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM receipt_history");
        jdbc.update("DELETE FROM cohort_migration_item");
        jdbc.update("DELETE FROM cohort_migration_order");
        jdbc.update("DELETE FROM assignment_command");
        jdbc.update("DELETE FROM cohort_assignment");
        jdbc.update("DELETE FROM cohort");
        jdbc.update("DELETE FROM cohort_region");
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

    private void setupCohorts(int seq) {
        String model = "m" + seq;
        releaseId = releaseService.create(
                new CreateReleaseRequest("req-rel" + seq, model, "1.0.0", "2.0.0", 100)).releaseId();
        cohortService.createRegion(new CreateRegionRequest("req-region" + seq, releaseId, "R1", 100));
        cohortA = cohortService.createCohort(new CreateCohortRequest("req-ca" + seq, releaseId, "A", "fw-9",
                "R1", 100, 100, 2, 50)).cohortId();
        cohortB = cohortService.createCohort(new CreateCohortRequest("req-cb" + seq, releaseId, "B", "fw-9",
                "R1", 100, 100, 2, 50)).cohortId();
        cohortC = cohortService.createCohort(new CreateCohortRequest("req-cc" + seq, releaseId, "C", "fw-9",
                "R1", 100, 100, 2, 50)).cohortId();
    }

    private long assign(String deviceId, long cohortId, String requestId) {
        return cohortService.assignDevice(
                new AssignDeviceRequest(requestId, releaseId, deviceId, cohortId)).commandId();
    }

    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
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
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void 两个迁移并发争夺同一设备_恰好一个成功另一个409_无部分迁移() throws Exception {
        setupCohorts(0);
        assign("d1", cohortA, "req-a1");

        List<Object> results = runConcurrently(List.of(
                () -> migrationService.activate(new MigrationItemInput.Activate(
                        "req-m1", "mk-1", releaseId,
                        List.of(new MigrationItemInput("d1", cohortA, 1, cohortB)))),
                () -> migrationService.activate(new MigrationItemInput.Activate(
                        "req-m2", "mk-2", releaseId,
                        List.of(new MigrationItemInput("d1", cohortA, 1, cohortC))))));

        long successes = results.stream().filter(r -> r instanceof MigrationActivateResponse).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        // 设备最终只属于一个目标队列，代次恰好推进一次到2，分配版本为2
        CohortAssignment finalAssignment = assignmentRepository.findAssignment(releaseId, "d1").orElseThrow();
        assertThat(finalAssignment.currentGeneration()).isEqualTo(2);
        assertThat(finalAssignment.assignmentVersion()).isEqualTo(2);
        assertThat(finalAssignment.cohortId()).isIn(cohortB, cohortC);
        // 只有一张已提交迁移单
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cohort_migration_order", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cohort_migration_item", Long.class)).isEqualTo(1);
        // 恰好一条新代次 PENDING 指令，旧指令 SUPERSEDED
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment_command WHERE device_id = 'd1' AND status = 'PENDING'",
                Long.class);
        Long superseded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment_command WHERE device_id = 'd1' AND status = 'SUPERSEDED'",
                Long.class);
        assertThat(pending).isEqualTo(1);
        assertThat(superseded).isEqualTo(1);
    }

    @Test
    void 并发互迁不同设备_两单都成功且队列规模为完整后态() throws Exception {
        setupCohorts(0);
        assign("d1", cohortA, "req-a1");
        assign("d2", cohortB, "req-b1");

        List<Object> results = runConcurrently(List.of(
                () -> migrationService.activate(new MigrationItemInput.Activate(
                        "req-m1", "mk-1", releaseId,
                        List.of(new MigrationItemInput("d1", cohortA, 1, cohortB)))),
                () -> migrationService.activate(new MigrationItemInput.Activate(
                        "req-m2", "mk-2", releaseId,
                        List.of(new MigrationItemInput("d2", cohortB, 1, cohortA))))));

        assertThat(results).noneMatch(r -> r instanceof Exception);
        assertThat(assignmentRepository.findAssignment(releaseId, "d1").orElseThrow().cohortId())
                .isEqualTo(cohortB);
        assertThat(assignmentRepository.findAssignment(releaseId, "d2").orElseThrow().cohortId())
                .isEqualTo(cohortA);
        // 完整后态：A、B 各1台（净交换），无设备丢失
        assertThat(cohortService.getCohort(cohortA).deviceCount()).isEqualTo(1);
        assertThat(cohortService.getCohort(cohortB).deviceCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cohort_migration_order", Long.class)).isEqualTo(2);
    }

    @Test
    void 迁移与旧代次回执并发_按提交顺序只有一种一致结果() throws Exception {
        for (int round = 0; round < 8; round++) {
            setupCohorts(round);
            String deviceId = "d" + round;
            long oldCmd = assign(deviceId, cohortA, "req-a" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> migrationService.activate(new MigrationItemInput.Activate(
                            "req-mig" + seq, "mk-" + seq, releaseId,
                            List.of(new MigrationItemInput(deviceId, cohortA, 1, cohortB)))),
                    (Callable<Object>) () -> cohortService.receipt(oldCmd,
                            new CommandReceiptRequest("req-rc" + seq, ReceiptResult.FAILED))));

            Object migrateResult = results.get(0);
            Object receiptResult = results.get(1);
            // 迁移始终成功（FAILED 不置安装确认）；回执要么先结算要么成为 LATE，均为业务内结果
            assertThat(migrateResult).isInstanceOf(MigrationActivateResponse.class);
            assertThat(receiptResult).isNotInstanceOf(RuntimeException.class);

            CohortAssignment assignment = assignmentRepository.findAssignment(releaseId, deviceId).orElseThrow();
            assertThat(assignment.cohortId()).isEqualTo(cohortB);
            assertThat(assignment.currentGeneration()).isEqualTo(2);

            String oldStatus = assignmentRepository.findCommandById(oldCmd).orElseThrow().status().name();
            int aFailed = cohortRepository.findCohortById(cohortA).orElseThrow().failedCount();
            int bFailed = cohortRepository.findCohortById(cohortB).orElseThrow().failedCount();
            Long lateCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM receipt_history WHERE device_id = ? AND result = 'LATE'",
                    Long.class, deviceId);

            if ("FAILED".equals(oldStatus)) {
                // 回执先提交：旧队列 A 结算 1 个失败；迁移随后在无未决指令下推进代次
                assertThat(aFailed).isEqualTo(1);
                assertThat(bFailed).isZero();
                assertThat(lateCount).isZero();
            } else {
                // 迁移先提交：旧指令被 SUPERSEDED，回执只存档 LATE，B 统计不受影响
                assertThat(oldStatus).isEqualTo("SUPERSEDED");
                assertThat(aFailed).isZero();
                assertThat(bFailed).isZero();
                assertThat(lateCount).isEqualTo(1);
            }
            // 目标队列必须存在且仅存在一条新代次 PENDING 指令
            Long newPending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM assignment_command WHERE device_id = ? AND generation = 2"
                            + " AND status = 'PENDING'", Long.class, deviceId);
            assertThat(newPending).isEqualTo(1);
        }
    }
}
