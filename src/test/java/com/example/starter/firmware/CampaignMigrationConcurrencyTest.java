package com.example.starter.firmware;

import com.example.starter.firmware.api.ActivateMigrationRequest;
import com.example.starter.firmware.api.CampaignReceiptRequest;
import com.example.starter.firmware.api.CampaignReceiptView;
import com.example.starter.firmware.api.CreateCampaignRequest;
import com.example.starter.firmware.api.CreateCohortRequest;
import com.example.starter.firmware.api.EnrollDeviceRequest;
import com.example.starter.firmware.api.MigrationItemRequest;
import com.example.starter.firmware.api.MigrationView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.AssignmentRepository;
import com.example.starter.firmware.repo.CohortRepository;
import com.example.starter.firmware.service.CampaignService;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.MigrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移并发边界测试：真实并发打到 H2 事务与行锁上，验证迁移、回执入账按提交顺序串行，
 * 统计、设备归属与活动状态来自同一结果。
 */
@SpringBootTest
class CampaignMigrationConcurrencyTest {

    @Autowired
    private CampaignService campaignService;

    @Autowired
    private MigrationService migrationService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private CohortRepository cohortRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM cohort_receipt");
        jdbc.update("DELETE FROM migration_item");
        jdbc.update("DELETE FROM migration_order");
        jdbc.update("DELETE FROM dispatch_command");
        jdbc.update("DELETE FROM device_assignment");
        jdbc.update("DELETE FROM cohort");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private long newCampaign(String suffix, int deviceCount) {
        long campaignId = campaignService.create(new CreateCampaignRequest("c-" + suffix,
                "camp-" + suffix)).campaignId();
        for (int i = 1; i <= deviceCount; i++) {
            deviceService.register(new RegisterDeviceRequest("r-d-" + suffix + "-" + i,
                    "d" + suffix + "-" + i, "m1", "1.0.0", 1));
        }
        return campaignId;
    }

    private long newCohort(long campaignId, String requestId, String code) {
        return campaignService.createCohort(campaignId, new CreateCohortRequest(requestId, code,
                "2.0.0", "R1", 100, 100, 100, null, null)).cohortId();
    }

    private void enroll(long campaignId, String deviceId, long cohortId, String requestId) {
        campaignService.enroll(campaignId, new EnrollDeviceRequest(requestId, deviceId, cohortId));
    }

    private ActivateMigrationRequest migrationRequest(String requestId, String key, long from,
                                                      long to, String... deviceIds) {
        List<MigrationItemRequest> items = java.util.Arrays.stream(deviceIds)
                .map(d -> new MigrationItemRequest(d, from, 1, to))
                .toList();
        return new ActivateMigrationRequest(requestId, key, items);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    @Test
    void 并发迁移同一批设备_仅一单生效_归属与统计一致() throws Exception {
        long campaignId = newCampaign("a", 3);
        long cohortA = newCohort(campaignId, "k-a-1", "A");
        long cohortB = newCohort(campaignId, "k-a-2", "B");
        enroll(campaignId, "da-1", cohortA, "e-a-1");
        enroll(campaignId, "da-2", cohortA, "e-a-2");
        enroll(campaignId, "da-3", cohortA, "e-a-3");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Object> m1 = () -> {
            ready.countDown();
            go.await();
            return migrationService.activate(campaignId,
                    migrationRequest("m-a-1", "mk-a-1", cohortA, cohortB, "da-1", "da-2"));
        };
        Callable<Object> m2 = () -> {
            ready.countDown();
            go.await();
            return migrationService.activate(campaignId,
                    migrationRequest("m-a-2", "mk-a-2", cohortA, cohortB, "da-1", "da-2"));
        };
        Future<Object> f1 = executor.submit(m1);
        Future<Object> f2 = executor.submit(m2);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        int successes = 0;
        int conflicts = 0;
        for (Future<Object> f : List.of(f1, f2)) {
            try {
                await(f);
                successes++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ApiException.class);
                assertThat(((ApiException) e.getCause()).status()).isEqualTo(HttpStatus.CONFLICT);
                conflicts++;
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        var d1 = assignmentRepository.find(campaignId, "da-1").orElseThrow();
        assertThat(d1.cohortId()).isEqualTo(cohortB);
        assertThat(d1.assignmentVersion()).isEqualTo(2);
        assertThat(d1.assignmentGeneration()).isEqualTo(2);
        assertThat(cohortRepository.findById(cohortA).orElseThrow().deviceCount()).isEqualTo(1);
        assertThat(cohortRepository.findById(cohortB).orElseThrow().deviceCount()).isEqualTo(2);
        Integer migrations = jdbc.queryForObject("SELECT COUNT(*) FROM migration_order",
                Integer.class);
        assertThat(migrations).isEqualTo(1);
    }

    @Test
    void 迁移与旧代次回执并发_按提交顺序得到唯一一致结果() throws Exception {
        long campaignId = newCampaign("b", 2);
        long cohortA = newCohort(campaignId, "k-b-1", "A");
        long cohortB = newCohort(campaignId, "k-b-2", "B");
        enroll(campaignId, "db-1", cohortA, "e-b-1");
        enroll(campaignId, "db-2", cohortA, "e-b-2");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Object> migration = () -> {
            ready.countDown();
            go.await();
            return migrationService.activate(campaignId,
                    migrationRequest("m-b-1", "mk-b-1", cohortA, cohortB, "db-1", "db-2"));
        };
        Callable<Object> receipt = () -> {
            ready.countDown();
            go.await();
            return campaignService.receipt(campaignId,
                    new CampaignReceiptRequest("rc-b-1", "db-1", 1, ReceiptResult.SUCCESS));
        };
        Future<Object> fMigration = executor.submit(migration);
        Future<Object> fReceipt = executor.submit(receipt);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        MigrationView migrationView = null;
        ApiException migrationError = null;
        try {
            migrationView = (MigrationView) await(fMigration);
        } catch (java.util.concurrent.ExecutionException e) {
            migrationError = (ApiException) e.getCause();
        }
        CampaignReceiptView receiptView = (CampaignReceiptView) await(fReceipt);

        var cohortAState = cohortRepository.findById(cohortA).orElseThrow();
        var cohortBState = cohortRepository.findById(cohortB).orElseThrow();
        var d1 = assignmentRepository.find(campaignId, "db-1").orElseThrow();
        if (migrationView != null) {
            // 迁移先提交：回执迟到，仅存档为 LATE，不改变任何队列统计
            assertThat(migrationError).isNull();
            assertThat(receiptView.disposition()).isEqualTo("LATE");
            assertThat(d1.cohortId()).isEqualTo(cohortB);
            assertThat(d1.assignmentGeneration()).isEqualTo(2);
            assertThat(cohortAState.successCount()).isZero();
            assertThat(cohortBState.successCount()).isZero();
        } else {
            // 回执先提交：按旧队列结算并确认安装成功，整单迁移随后 422 失败
            assertThat(migrationError).isNotNull();
            assertThat(migrationError.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(receiptView.disposition()).isEqualTo("SETTLED");
            assertThat(d1.cohortId()).isEqualTo(cohortA);
            assertThat(d1.assignmentGeneration()).isEqualTo(1);
            assertThat(cohortAState.successCount()).isEqualTo(1);
            assertThat(cohortBState.successCount()).isZero();
            assertThat(cohortBState.deviceCount()).isZero();
        }
    }

    @Test
    void 同requestId并发激活_重放同一快照_只落一单() throws Exception {
        long campaignId = newCampaign("c", 2);
        long cohortA = newCohort(campaignId, "k-c-1", "A");
        long cohortB = newCohort(campaignId, "k-c-2", "B");
        enroll(campaignId, "dc-1", cohortA, "e-c-1");
        enroll(campaignId, "dc-2", cohortA, "e-c-2");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<MigrationView> call = () -> {
            ready.countDown();
            go.await();
            return migrationService.activate(campaignId,
                    migrationRequest("m-c-1", "mk-c-1", cohortA, cohortB, "dc-1", "dc-2"));
        };
        Future<MigrationView> f1 = executor.submit(call);
        Future<MigrationView> f2 = executor.submit(call);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        MigrationView v1 = await(f1);
        MigrationView v2 = await(f2);
        assertThat(v1.migrationKey()).isEqualTo("mk-c-1");
        assertThat(v2.migrationKey()).isEqualTo("mk-c-1");
        assertThat(v1.deviceCount()).isEqualTo(2);
        Integer migrations = jdbc.queryForObject("SELECT COUNT(*) FROM migration_order",
                Integer.class);
        Integer idempotencyRecords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'm-c-1'", Integer.class);
        assertThat(migrations).isEqualTo(1);
        assertThat(idempotencyRecords).isEqualTo(1);
        assertThat(cohortRepository.findById(cohortB).orElseThrow().deviceCount()).isEqualTo(2);
    }

    @Test
    void 并发同代次回执_只结算一次() throws Exception {
        long campaignId = newCampaign("d", 1);
        long cohortA = newCohort(campaignId, "k-d-1", "A");
        enroll(campaignId, "dd-1", cohortA, "e-d-1");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<CampaignReceiptView> r1 = () -> {
            ready.countDown();
            go.await();
            return campaignService.receipt(campaignId,
                    new CampaignReceiptRequest("rc-d-1", "dd-1", 1, ReceiptResult.SUCCESS));
        };
        Callable<CampaignReceiptView> r2 = () -> {
            ready.countDown();
            go.await();
            return campaignService.receipt(campaignId,
                    new CampaignReceiptRequest("rc-d-2", "dd-1", 1, ReceiptResult.SUCCESS));
        };
        Future<CampaignReceiptView> f1 = executor.submit(r1);
        Future<CampaignReceiptView> f2 = executor.submit(r2);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        assertThat(await(f1).disposition()).isEqualTo("SETTLED");
        assertThat(await(f2).disposition()).isEqualTo("SETTLED");
        assertThat(cohortRepository.findById(cohortA).orElseThrow().successCount()).isEqualTo(1);
        Integer receipts = jdbc.queryForObject("SELECT COUNT(*) FROM cohort_receipt",
                Integer.class);
        assertThat(receipts).isEqualTo(1);
    }
}
