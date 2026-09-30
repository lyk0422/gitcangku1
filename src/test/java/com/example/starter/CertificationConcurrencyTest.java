package com.example.starter;

import com.example.starter.service.CertificationService;
import com.example.starter.support.ConcurrencyHooks;
import com.example.starter.web.dto.Requests;
import com.example.starter.web.error.BusinessException;
import com.example.starter.web.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发作废与放行的提交顺序测试（真实 H2 行锁与事务，非 mock）：
 * 用 CountDownLatch 协调两个真实业务线程的交错，整体带超时，最终核对数据库状态而非打印。
 */
@SpringBootTest
@Import({FixedClockTestConfiguration.class, CertificationConcurrencyTest.CoordinatorConfiguration.class})
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:cert-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class CertificationConcurrencyTest {

    private static final String BATCH = "UT-CONC-BATCH";
    private static final String TEST_1 = "T-C-1";
    private static final String TEST_2 = "T-C-2";
    private static final String RELEASE_REQUEST_ID = "REQ-RELEASE-C";

    @Autowired
    private CertificationService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Coordinator coordinator;

    @TestConfiguration
    static class CoordinatorConfiguration {

        @Bean
        @Primary
        Coordinator testCoordinator() {
            return new Coordinator();
        }
    }

    /**
     * 既是并发钩子又是线程间协调器：mode 决定当前用例需要的交错方式。
     */
    static class Coordinator extends ConcurrencyHooks {

        volatile String mode = "";
        volatile CountDownLatch voidReady = new CountDownLatch(1);
        volatile CountDownLatch releaseStarted = new CountDownLatch(1);
        volatile CountDownLatch releaseLocked = new CountDownLatch(1);
        volatile CountDownLatch voidStarted = new CountDownLatch(1);

        void reset(String newMode) {
            this.mode = newMode;
            voidReady = new CountDownLatch(1);
            releaseStarted = new CountDownLatch(1);
            releaseLocked = new CountDownLatch(1);
            voidStarted = new CountDownLatch(1);
        }

        @Override
        public void beforeVoidCommit(String batchNo) {
            if ("VOID_FIRST".equals(mode)) {
                voidReady.countDown();
                await(releaseStarted);
                // 放行线程已发起并在批次行锁上排队；有界等待确保它进入锁等待队列后再提交作废。
                sleep(300);
            }
        }

        @Override
        public void afterReleaseLockAcquired(String batchNo) {
            if ("RELEASE_FIRST".equals(mode)) {
                releaseLocked.countDown();
                await(voidStarted);
                // 作废线程已发起并在批次行锁上排队；保证它进入锁等待队列后再提交放行。
                sleep(300);
            }
        }

        private static void await(CountDownLatch latch) {
            try {
                assertTrue(latch.await(10, TimeUnit.SECONDS), "并发协调等待超时");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM batch_release");
        jdbcTemplate.update("DELETE FROM germination_certificate");
        jdbcTemplate.update("DELETE FROM germination_test");
        jdbcTemplate.update("DELETE FROM seed_batch");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        service.createBatch(new Requests.CreateBatchRequest(BATCH, "合成玉米"));
        service.registerTest(BATCH, new Requests.RegisterTestRequest(
                TEST_1, 100, 86, LocalDateTime.of(2026, 9, 15, 9, 0)));
        service.registerTest(BATCH, new Requests.RegisterTestRequest(
                TEST_2, 100, 90, LocalDateTime.of(2026, 9, 15, 9, 30)));
        service.issueCertificate(BATCH, new Requests.IssueCertificateRequest("REQ-ISSUE-C"));
    }

    /**
     * 作废先提交：证书先变为 INVALID，放行拿到行锁后读到失效证书，返回 409，
     * 不产生放行事实与幂等记录；不能凭失效证书完成放行。
     */
    @Test
    void voidCommitsFirst_releaseFailsOnInvalidCertificate() throws Exception {
        coordinator.reset("VOID_FIRST");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> voidFuture = pool.submit(() -> service.voidTest(BATCH, TEST_1));
            assertTrue(coordinator.voidReady.await(10, TimeUnit.SECONDS));

            Future<?> releaseFuture = pool.submit(() -> {
                coordinator.releaseStarted.countDown();
                service.release(BATCH, new Requests.ReleaseRequest(RELEASE_REQUEST_ID));
            });

            voidFuture.get(30, TimeUnit.SECONDS);
            BusinessException ex = unwrap(releaseFuture);
            assertEquals(ErrorCode.NO_VALID_CERTIFICATE, ex.errorCode());

            assertEquals("INVALID", queryCertificateStatus(), "未放行证书应已失效");
            assertEquals(0, queryReleaseCount(), "失效证书不得产生放行事实");
            assertEquals(0, queryIdempotencyCount(), "放行事务回滚，不得留下幂等记录");
            assertEquals("VOID", queryTestStatus(TEST_1));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 放行先提交：放行依据当时 VALID 证书成功；作废随后提交，已放行批次保留证书快照与放行事实。
     */
    @Test
    void releaseCommitsFirst_voidKeepsSnapshot() throws Exception {
        coordinator.reset("RELEASE_FIRST");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> releaseFuture = pool.submit(() ->
                    service.release(BATCH, new Requests.ReleaseRequest(RELEASE_REQUEST_ID)));
            assertTrue(coordinator.releaseLocked.await(10, TimeUnit.SECONDS));

            Future<?> voidFuture = pool.submit(() -> {
                coordinator.voidStarted.countDown();
                service.voidTest(BATCH, TEST_1);
            });

            releaseFuture.get(30, TimeUnit.SECONDS);
            voidFuture.get(30, TimeUnit.SECONDS);

            assertEquals("VALID", queryCertificateStatus(), "已放行批次的证书快照不回滚");
            assertEquals(1, queryReleaseCount(), "放行事实保留");
            assertEquals(1, queryIdempotencyCount());
            assertEquals("VOID", queryTestStatus(TEST_1), "检测记录仍被作废");
        } finally {
            pool.shutdownNow();
        }
    }

    private static BusinessException unwrap(Future<?> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            throw new AssertionError("预期放行被业务异常拒绝");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof BusinessException business) {
                return business;
            }
            throw e;
        }
    }

    private String queryCertificateStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM germination_certificate WHERE batch_id = "
                        + "(SELECT id FROM seed_batch WHERE batch_no = ?)",
                String.class, BATCH);
    }

    private int queryReleaseCount() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_release WHERE batch_id = "
                        + "(SELECT id FROM seed_batch WHERE batch_no = ?)",
                Integer.class, BATCH);
        return c == null ? 0 : c;
    }

    private int queryIdempotencyCount() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE scope = 'RELEASE' AND request_id = ?",
                Integer.class, RELEASE_REQUEST_ID);
        return c == null ? 0 : c;
    }

    private String queryTestStatus(String testNo) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM germination_test WHERE test_no = ?", String.class, testNo);
    }
}
