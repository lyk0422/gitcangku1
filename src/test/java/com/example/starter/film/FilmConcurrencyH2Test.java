package com.example.starter.film;

import com.example.starter.film.exception.ConflictException;
import com.example.starter.film.model.DeliveryReceipt;
import com.example.starter.film.model.Film;
import com.example.starter.film.model.Master;
import com.example.starter.film.model.MasterSegment;
import com.example.starter.film.model.ReviewRequest;
import com.example.starter.film.model.ReviewStatus;
import com.example.starter.film.model.SegmentVersion;
import com.example.starter.film.repo.MasterRepository;
import com.example.starter.film.repo.ReviewRepository;
import com.example.starter.film.service.ApproveOutcome;
import com.example.starter.film.service.FilmService;
import com.example.starter.film.support.ConcurrencyHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 H2 多线程并发边界：审核通过与片段更新竞争、交付重试竞争。
 * 用 CountDownLatch 协调真实事务的锁顺序，所有等待带超时，避免靠睡眠判定。
 */
@SpringBootTest
class FilmConcurrencyH2Test {

    @Autowired
    private FilmService service;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private MasterRepository masterRepository;
    @Autowired
    private ConcurrencyHooks hooks;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE delivery_receipt");
        jdbc.execute("TRUNCATE TABLE master_segment");
        jdbc.execute("TRUNCATE TABLE master");
        jdbc.execute("TRUNCATE TABLE review_segment_baseline");
        jdbc.execute("TRUNCATE TABLE review_request");
        jdbc.execute("TRUNCATE TABLE segment_version");
        jdbc.execute("TRUNCATE TABLE idempotent_request");
        jdbc.execute("TRUNCATE TABLE film");
        pool = Executors.newFixedThreadPool(2);
        hooks.clear();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        hooks.clear();
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    private Film preparedFilmWithPendingReview() {
        Film film = service.createFilm("F-" + UUID.randomUUID().toString().substring(0, 8),
                "并发影片", 2);
        service.submitSegment(film.id(), 1, "seg1-v1", 0, "s1-" + UUID.randomUUID());
        service.submitSegment(film.id(), 2, "seg2-v1", 0, "s2-" + UUID.randomUUID());
        return film;
    }

    @Test
    void approveFirst_thenConcurrentSegmentUpdate_masterKeepsOldInstantVersions() throws Exception {
        Film film = preparedFilmWithPendingReview();
        ReviewRequest review = service.createReview(film.id(), "rv-" + UUID.randomUUID());

        CountDownLatch approveHoldsLock = new CountDownLatch(1);
        CountDownLatch letSubmitProceed = new CountDownLatch(1);
        CountDownLatch letApproveFinish = new CountDownLatch(1);
        CountDownLatch submitStarted = new CountDownLatch(1);

        hooks.setBeforeSubmitLock(() -> {
            submitStarted.countDown();
            await(letSubmitProceed);
        });
        hooks.setInsideApproveAfterLock(() -> {
            approveHoldsLock.countDown();
            await(letApproveFinish);
        });

        AtomicReference<ApproveOutcome> approveResult = new AtomicReference<>();
        Future<?> approveFuture = pool.submit(() ->
                approveResult.set(service.approveReview(film.id(), review.id(),
                        "ap-" + UUID.randomUUID())));

        // 审核事务已持有影片行锁
        assertTrue(approveHoldsLock.await(5, TimeUnit.SECONDS));

        Future<?> submitFuture = pool.submit(() -> service.submitSegment(
                film.id(), 2, "seg2-v2", 1, "s2new-" + UUID.randomUUID()));
        assertTrue(submitStarted.await(5, TimeUnit.SECONDS));

        // 放行提交（将阻塞在 FOR UPDATE），再放行审核：母版只能引用同一瞬间的两段版本
        letSubmitProceed.countDown();
        letApproveFinish.countDown();
        approveFuture.get(5, TimeUnit.SECONDS);
        submitFuture.get(5, TimeUnit.SECONDS);

        Master master = approveResult.get().master();
        List<MasterSegment> snap = masterRepository.findSegments(master.id());
        assertEquals("seg1-v1", snap.get(0).digest());
        assertEquals("seg2-v1", snap.get(1).digest());
        assertEquals(1, snap.get(1).versionNo());

        // 提交最终生效：工作版第2段为 v2，但母版不读取它
        Integer latest2 = jdbc.queryForObject(
                "SELECT version_no FROM segment_version WHERE film_id = ? AND seq = 2 "
                        + "ORDER BY version_no DESC LIMIT 1",
                Integer.class, film.id());
        assertEquals(Integer.valueOf(2), latest2);
        assertEquals(ReviewStatus.APPROVED,
                reviewRepository.findById(review.id()).orElseThrow().status());
    }

    @Test
    void segmentUpdateFirst_thenApproveFails409_andLeavesNoMaster() throws Exception {
        Film film = preparedFilmWithPendingReview();
        ReviewRequest review = service.createReview(film.id(), "rv-" + UUID.randomUUID());

        CountDownLatch submitHoldsLock = new CountDownLatch(1);
        CountDownLatch letSubmitFinish = new CountDownLatch(1);
        CountDownLatch approveReady = new CountDownLatch(1);
        CountDownLatch letApproveStart = new CountDownLatch(1);

        hooks.setInsideSubmitAfterLock(() -> {
            submitHoldsLock.countDown();
            await(letSubmitFinish);
        });
        hooks.setBeforeApproveLock(() -> {
            approveReady.countDown();
            await(letApproveStart);
        });

        Future<?> submitFuture = pool.submit(() -> service.submitSegment(
                film.id(), 2, "seg2-v2", 1, "s2new-" + UUID.randomUUID()));
        assertTrue(submitHoldsLock.await(5, TimeUnit.SECONDS));

        AtomicReference<Throwable> approveError = new AtomicReference<>();
        Future<?> approveFuture = pool.submit(() -> {
            try {
                service.approveReview(film.id(), review.id(), "ap-" + UUID.randomUUID());
            } catch (Throwable t) {
                approveError.set(t);
            }
        });
        assertTrue(approveReady.await(5, TimeUnit.SECONDS));

        // 提交先完成：审核过期；审核再拿锁必须失败，不能混用 seg2 新旧摘要
        letApproveStart.countDown();
        letSubmitFinish.countDown();
        submitFuture.get(5, TimeUnit.SECONDS);
        approveFuture.get(5, TimeUnit.SECONDS);

        Throwable t = approveError.get();
        assertNotNull(t);
        assertTrue(t instanceof ConflictException, "应为409冲突，实际: " + t);
        assertEquals(ReviewStatus.EXPIRED,
                reviewRepository.findById(review.id()).orElseThrow().status());
        assertEquals(0, masterRepository.findByFilm(film.id()).size());
    }

    @Test
    void concurrentDeliveryWithSameRequestId_createsExactlyOneReceipt() throws Exception {
        Film film = preparedFilmWithPendingReview();
        ReviewRequest review = service.createReview(film.id(), "rv-" + UUID.randomUUID());
        Master master = service.approveReview(film.id(), review.id(), "ap-" + UUID.randomUUID())
                .master();
        String requestId = "dl-concurrent-" + UUID.randomUUID();

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<DeliveryReceipt> r1 = new AtomicReference<>();
        AtomicReference<DeliveryReceipt> r2 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();

        Future<?> f1 = pool.submit(() -> {
            await(start);
            r1.set(service.deliver(film.id(), master.masterNo(), requestId));
        });
        Future<?> f2 = pool.submit(() -> {
            await(start);
            try {
                r2.set(service.deliver(film.id(), master.masterNo(), requestId));
            } catch (Throwable t) {
                e2.set(t);
            }
        });
        start.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM delivery_receipt WHERE request_id = ?",
                Integer.class, requestId);
        assertEquals(Integer.valueOf(1), count);
        // 两个调用要么都拿到同一张回执，要么其一在唯一约束竞争中失败后由事务回滚
        if (e2.get() == null) {
            assertEquals(r1.get().id(), r2.get().id());
        } else {
            assertNull(r2.get());
            assertTrue(org.springframework.dao.DataAccessException.class
                    .isAssignableFrom(e2.get().getClass()));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "并发协调超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
