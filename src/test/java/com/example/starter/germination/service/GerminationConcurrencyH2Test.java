package com.example.starter.germination.service;

import com.example.starter.germination.AbstractGerminationH2Test;
import com.example.starter.germination.repository.GerminationRepository;
import com.example.starter.germination.web.CertificateView;
import com.example.starter.germination.web.IssueCertificateRequest;
import com.example.starter.germination.web.ReleaseBatchRequest;
import com.example.starter.germination.web.RegisterTestRequest;
import com.example.starter.germination.web.VoidTestRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 真实 H2 上的并发测试：作废与放行以提交顺序串行化、重复放行竞争、
 * 同一检测编号并发登记由数据库唯一约束保证只归属一个批次。
 * 所有线程操作通过闭锁协调，并设置超时，避免靠休眠判断结果。
 */
class GerminationConcurrencyH2Test extends AbstractGerminationH2Test {

    private static final String TAG = "ITCJ-";

    @Autowired
    private GerminationService service;

    @SpyBean
    private GerminationRepository repositorySpy;

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected void setupScenario() {
        createBatch(TAG + "B1", "合成水稻");
        createBatch(TAG + "B2", "合成玉米");
        createBatch(TAG + "B3", "合成小麦");
    }

    private void issueValidCertificate(String batchNo) {
        service.registerTest(new RegisterTestRequest(batchNo + "-T1", batchNo, 100, 86,
                batchNo + "-REG1"));
        service.registerTest(new RegisterTestRequest(batchNo + "-T2", batchNo, 100, 90,
                batchNo + "-REG2"));
        service.issueCertificate(new IssueCertificateRequest(batchNo, batchNo + "-ISS"));
    }

    /** 放行先提交：作废随后看到放行事实，证书快照保留 VALID，批次保持已放行。 */
    @Test
    void releaseCommitsFirst_voidKeepsSnapshotAndReleaseFact() throws Exception {
        String batchNo = TAG + "B1";
        issueValidCertificate(batchNo);
        CertificateView cert = service.getBatchChain(batchNo).certificate();

        CountDownLatch releasePaused = new CountDownLatch(1);
        CountDownLatch proceedRelease = new CountDownLatch(1);
        AtomicReference<Boolean> pausedOnce = new AtomicReference<>(false);
        Mockito.doAnswer(invocation -> {
            if (pausedOnce.compareAndSet(false, true)) {
                // 放行已持批次行锁并完成放行事实检查，在此暂停，让作废线程先卡在锁上
                releasePaused.countDown();
                assertThat(proceedRelease.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(repositorySpy).findCertificateByBatchForUpdate(eq(batchNo));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> releaseFuture = pool.submit(() ->
                    service.releaseBatch(new ReleaseBatchRequest(batchNo, TAG + "B1-REL")));
            assertThat(releasePaused.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> voidFuture = pool.submit(() ->
                    service.voidTest(new VoidTestRequest(TAG + "B1-T1", batchNo, TAG + "B1-VOID")));
            // 给作废线程时间进入 lockBatch 并阻塞在放行持有的行锁上
            Thread.sleep(300);

            proceedRelease.countDown();

            releaseFuture.get(10, TimeUnit.SECONDS);
            voidFuture.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        var chain = service.getBatchChain(batchNo);
        assertThat(chain.release().released()).isTrue();
        assertThat(chain.release().certNo()).isEqualTo(cert.certNo());
        assertThat(chain.certificate().status()).isEqualTo("VALID");
        assertThat(chain.tests().get(0).status()).isEqualTo("VOID");
    }

    /** 作废先提交：证书失效；随后放行只能看到 INVALID 证书并返回 409。 */
    @Test
    void voidCommitsFirst_releaseFailsOnInvalidCertificate() throws Exception {
        String batchNo = TAG + "B2";
        issueValidCertificate(batchNo);

        CountDownLatch voidPaused = new CountDownLatch(1);
        CountDownLatch proceedVoid = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            // 作废已持批次行锁、尚未写作废状态，在此暂停，放行线程卡在锁上
            voidPaused.countDown();
            assertThat(proceedVoid.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(repositorySpy).markTestVoid(eq(TAG + "B2-T1"), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
        try {
            Future<?> voidFuture = pool.submit(() ->
                    service.voidTest(new VoidTestRequest(TAG + "B2-T1", batchNo, TAG + "B2-VOID")));
            assertThat(voidPaused.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> releaseFuture = pool.submit(() -> {
                try {
                    service.releaseBatch(new ReleaseBatchRequest(batchNo, TAG + "B2-REL"));
                } catch (Throwable t) {
                    releaseFailure.set(t);
                }
            });
            Thread.sleep(300);

            proceedVoid.countDown();

            voidFuture.get(10, TimeUnit.SECONDS);
            releaseFuture.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(releaseFailure.get())
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getReason())
                        .isEqualTo(RejectReason.CERTIFICATE_INVALID));
        var chain = service.getBatchChain(batchNo);
        assertThat(chain.release().released()).isFalse();
        assertThat(chain.certificate().status()).isEqualTo("INVALID");
    }

    /** 两个放行并发：恰好一个成功，另一个以 409 拒绝，不产生第二条放行事实。 */
    @Test
    void concurrentReleasesExactlyOneSucceeds() throws Exception {
        String batchNo = TAG + "B3";
        issueValidCertificate(batchNo);

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();
        try {
            Future<?> fa = pool.submit(() -> {
                awaitGate(startGate);
                try {
                    service.releaseBatch(new ReleaseBatchRequest(batchNo, TAG + "B3-REL-A"));
                } catch (Throwable t) {
                    failureA.set(t);
                }
            });
            Future<?> fb = pool.submit(() -> {
                awaitGate(startGate);
                try {
                    service.releaseBatch(new ReleaseBatchRequest(batchNo, TAG + "B3-REL-B"));
                } catch (Throwable t) {
                    failureB.set(t);
                }
            });
            startGate.countDown();
            fa.get(15, TimeUnit.SECONDS);
            fb.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        long successCount = (failureA.get() == null ? 1 : 0) + (failureB.get() == null ? 1 : 0);
        assertThat(successCount).isEqualTo(1);
        Throwable loser = failureA.get() != null ? failureA.get() : failureB.get();
        assertThat(loser).isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) loser).getReason())
                .isEqualTo(RejectReason.BATCH_ALREADY_RELEASED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM batch_release WHERE batch_no = ?",
                Integer.class, batchNo)).isEqualTo(1);
    }

    /** 同一检测编号并发登记到两个批次：唯一约束保证只有一行、只归属一个批次。 */
    @Test
    void concurrentSameTestNoOnlyOneBatchWins() throws Exception {
        String sharedTestNo = TAG + "SHARED-T";
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();
        try {
            Future<?> fa = pool.submit(() -> {
                awaitGate(startGate);
                try {
                    service.registerTest(new RegisterTestRequest(sharedTestNo, TAG + "B1", 100, 90,
                            TAG + "REG-A"));
                } catch (Throwable t) {
                    failureA.set(t);
                }
            });
            Future<?> fb = pool.submit(() -> {
                awaitGate(startGate);
                try {
                    service.registerTest(new RegisterTestRequest(sharedTestNo, TAG + "B2", 100, 90,
                            TAG + "REG-B"));
                } catch (Throwable t) {
                    failureB.set(t);
                }
            });
            startGate.countDown();
            fa.get(15, TimeUnit.SECONDS);
            fb.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        long successCount = (failureA.get() == null ? 1 : 0) + (failureB.get() == null ? 1 : 0);
        assertThat(successCount).isEqualTo(1);
        Throwable loser = failureA.get() != null ? failureA.get() : failureB.get();
        assertThat(loser).isInstanceOf(BusinessRejectedException.class);
        assertThat(((BusinessRejectedException) loser).getReason())
                .isIn(RejectReason.TEST_NO_DUPLICATED, RejectReason.TEST_BELONGS_TO_OTHER_BATCH);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM germination_test WHERE test_no = ?",
                Integer.class, sharedTestNo)).isEqualTo(1);
    }

    /** 等待并发发令闸开启，中断或超时即视为用例失败。 */
    private static void awaitGate(CountDownLatch gate) {
        try {
            assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("并发用例等待发令闸时被中断", e);
        }
    }
}
