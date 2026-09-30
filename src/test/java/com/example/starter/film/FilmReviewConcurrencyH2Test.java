package com.example.starter.film;

import com.example.starter.film.api.ApiException;
import com.example.starter.film.api.ReviewRequest;
import com.example.starter.film.api.ReviewResponse;
import com.example.starter.film.api.SegmentSubmitRequest;
import com.example.starter.film.api.SegmentSubmitResponse;
import com.example.starter.film.service.FilmServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 片段更新与整片审核并发的真实 H2 + 真实线程测试。
 *
 * <p>用服务内的测试屏障把第 2 段新提交确定地排在审核事务的行锁之后，
 * 断言母版只能引用同一瞬间的两段版本（全旧或全新），绝不混用前后摘要。
 */
@SpringBootTest
class FilmReviewConcurrencyH2Test {

    @Autowired
    private FilmServiceImpl filmService;

    @Autowired
    private JdbcTemplate jdbc;

    private String code;
    private Long filmId;

    @BeforeEach
    void setUp() {
        FilmServiceImpl.reviewBarrier = () -> { };
        jdbc.update("DELETE FROM delivery_receipt");
        jdbc.update("DELETE FROM master_segment");
        jdbc.update("DELETE FROM master");
        jdbc.update("DELETE FROM segment_version");
        jdbc.update("DELETE FROM film");

        code = "F-" + UUID.randomUUID().toString().substring(0, 8);
        filmId = filmService.registerFilm(code, 2);
        filmService.submitSegment(code,
                new SegmentSubmitRequest(1, TestData.digest("seg1-v1"), null, "s1"));
        filmService.submitSegment(code,
                new SegmentSubmitRequest(2, TestData.digest("seg2-v1"), null, "s2"));
    }

    @Test
    void 审核锁定更新前集合时_并发的第2段提交不得混入母版() throws Exception {
        CountDownLatch reviewReachedBarrier = new CountDownLatch(1);
        CountDownLatch submitMayStart = new CountDownLatch(1);
        CountDownLatch submitStarted = new CountDownLatch(1);
        FilmServiceImpl.reviewBarrier = () -> {
            reviewReachedBarrier.countDown();
            try {
                // 等第 2 段新提交进入并阻塞在影片行锁上，再放行审核读取片段集合。
                assertTrue(submitMayStart.await(10, TimeUnit.SECONDS));
                assertTrue(submitStarted.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        };

        try {
            CompletableFuture<ReviewResponse> reviewFuture = CompletableFuture.supplyAsync(
                    () -> filmService.review(code, new ReviewRequest("rv-race")));

            assertTrue(reviewReachedBarrier.await(10, TimeUnit.SECONDS));

            CompletableFuture<SegmentSubmitResponse> submitFuture = CompletableFuture.supplyAsync(() ->
                    filmService.submitSegment(code, new SegmentSubmitRequest(
                            2, TestData.digest("seg2-v2"), 1, "s2-v2")));

            submitMayStart.countDown();
            // 给提交线程时间执行到 lockFilm 并进入锁等待。
            Thread.sleep(300);
            submitStarted.countDown();

            ReviewResponse review = reviewFuture.get(15, TimeUnit.SECONDS);
            SegmentSubmitResponse submitted = submitFuture.get(15, TimeUnit.SECONDS);

            // 母版与提交都成功，但母版固定的是更新前瞬间：两段都是版次 1。
            assertEquals(2, submitted.versionNo());
            assertEquals(2, review.segments().size());
            assertEquals(1, review.segments().get(0).versionNo());
            assertEquals(1, review.segments().get(1).versionNo());
            assertEquals(TestData.digest("seg2-v1"), review.segments().get(1).contentDigest());

            // 母版两行快照全部存在，且没有任何一行引用新摘要。
            Long mixed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM master_segment ms JOIN master m ON ms.master_id = m.id "
                            + "WHERE m.film_id = ? AND ms.content_digest = ?",
                    Long.class, filmId, TestData.digest("seg2-v2"));
            assertEquals(0L, mixed);
            Long snapshotRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM master_segment ms JOIN master m ON ms.master_id = m.id "
                            + "WHERE m.film_id = ?", Long.class, filmId);
            assertEquals(2L, snapshotRows);
        } finally {
            FilmServiceImpl.reviewBarrier = () -> { };
        }
    }

    @Test
    void 提交先提交后审核_母版固定的是更新后的完整集合() throws Exception {
        filmService.submitSegment(code, new SegmentSubmitRequest(
                2, TestData.digest("seg2-v2"), 1, "s2-v2-before"));

        ReviewResponse review = filmService.review(code, new ReviewRequest("rv-after"));
        assertEquals(1, review.segments().get(0).versionNo());
        assertEquals(2, review.segments().get(1).versionNo());
        assertEquals(TestData.digest("seg2-v2"), review.segments().get(1).contentDigest());
    }

    @Test
    void 并发审核与提交在任一交错下都不出现新旧混用() throws Exception {
        // 无屏障的真实竞争，重复执行多轮；每次成功审核的母版段集合必须内部一致。
        for (int round = 0; round < 5; round++) {
            String roundCode = "F-R" + round + "-" + UUID.randomUUID().toString().substring(0, 8);
            final String filmCode = roundCode;
            final String reviewRequestId = "rv-" + round;
            final String submitRequestId = "s2v2-" + round;
            long roundFilmId = filmService.registerFilm(roundCode, 2);
            filmService.submitSegment(roundCode,
                    new SegmentSubmitRequest(1, TestData.digest(roundCode + "-s1"), null, "s1"));
            filmService.submitSegment(roundCode,
                    new SegmentSubmitRequest(2, TestData.digest(roundCode + "-s2-v1"), null, "s2"));

            CompletableFuture<ReviewResponse> reviewFuture = CompletableFuture.supplyAsync(
                    () -> filmService.review(filmCode, new ReviewRequest(reviewRequestId)));
            CompletableFuture<SegmentSubmitResponse> submitFuture = CompletableFuture.supplyAsync(() ->
                    filmService.submitSegment(filmCode, new SegmentSubmitRequest(
                            2, TestData.digest(filmCode + "-s2-v2"), 1, submitRequestId)));

            // 两段均已提交：行锁串行化下审核与提交都成功，但母版段集合必须内部一致。
            ReviewResponse review = reviewFuture.get(15, TimeUnit.SECONDS);
            SegmentSubmitResponse submitted = submitFuture.get(15, TimeUnit.SECONDS);
            assertEquals(2, submitted.versionNo());

            Long masterCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM master WHERE film_id = ?", Long.class, roundFilmId);
            assertEquals(1L, masterCount);
            // 同一张母版内：第 1 段永远 v1；第 2 段要么全 v1 要么全 v2，快照行数恒为 2。
            assertEquals(1, review.segments().get(0).versionNo());
            assertTrue(review.segments().get(1).versionNo() == 1
                    || review.segments().get(1).versionNo() == 2);
            Long rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM master_segment ms JOIN master m ON ms.master_id = m.id "
                            + "WHERE m.film_id = ?", Long.class, roundFilmId);
            assertEquals(2L, rows);
            Long newDigestInMaster = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM master_segment ms JOIN master m ON ms.master_id = m.id "
                            + "WHERE m.film_id = ? AND ms.version_no = 2 "
                            + "AND ms.content_digest <> ?",
                    Long.class, roundFilmId, TestData.digest(roundCode + "-s2-v2"));
            assertEquals(0L, newDigestInMaster);
        }
    }

    @Test
    void 并发同requestId片段提交只产生一条版本记录() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        int threads = 6;
        java.util.List<CompletableFuture<SegmentSubmitResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return filmService.submitSegment(code, new SegmentSubmitRequest(
                        1, TestData.digest("seg1-v1"), null, "s1"));
            }));
        }
        start.countDown();

        Integer versionNo = null;
        for (CompletableFuture<SegmentSubmitResponse> f : futures) {
            SegmentSubmitResponse response = f.get(15, TimeUnit.SECONDS);
            if (versionNo == null) {
                versionNo = response.versionNo();
            }
            assertEquals(versionNo, response.versionNo());
        }
        // "s1" 是初始化已用的 requestId，全部请求都重放为版次 1，不产生新记录。
        assertEquals(1, versionNo);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_version WHERE film_id = ? AND request_id = 's1'",
                Long.class, filmId);
        assertEquals(1L, count);
    }
}
