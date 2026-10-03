package com.example.starter.dive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.StarterApplication;
import com.example.starter.dive.DiveDtos.AppendPointsRequest;
import com.example.starter.dive.DiveDtos.CreateLogRequest;
import com.example.starter.dive.DiveDtos.LogDto;
import com.example.starter.dive.DiveDtos.PointInput;
import com.example.starter.dive.DiveDtos.ReviewDto;
import com.example.starter.dive.DiveDtos.ReviewRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * 并发边界测试：并发审核同一版本最多一条通过决定；追加与审核竞争时审核读取完整一致的点位集合。
 */
@SpringBootTest(classes = {StarterApplication.class, TestClockConfig.class})
@Sql(statements = {
        "DELETE FROM re_review",
        "DELETE FROM dive_review",
        "DELETE FROM idempotency_record",
        "DELETE FROM depth_point",
        "DELETE FROM dive_log"
})
class DiveLogConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-10-04T10:00:00Z");

    @Autowired
    private DiveLogService service;

    @Autowired
    private JdbcTemplate jdbc;

    private LogDto newLog(String requestId) {
        return service.createLog(new CreateLogRequest(requestId, 30, 40, T0,
                T0.plus(Duration.ofMinutes(40))));
    }

    private List<PointInput> validPoints() {
        List<PointInput> points = new ArrayList<>();
        double[] depths = {0, 10, 20, 28, 25, 18, 12, 6, 0};
        for (int i = 0; i < depths.length; i++) {
            points.add(new PointInput(T0.plus(Duration.ofMinutes(5L * i)), depths[i]));
        }
        return points;
    }

    @Test
    void concurrentReviewSameVersionYieldsSingleApproval() throws Exception {
        LogDto log = newLog("cc-create-1");
        service.appendPoints(log.id(), new AppendPointsRequest("cc-up-1", validPoints()));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReviewDto>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return service.review(log.id(), new ReviewRequest("cc-rv-" + n));
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();

        long firstReviewId = -1;
        for (Future<ReviewDto> f : futures) {
            ReviewDto dto = f.get(30, TimeUnit.SECONDS);
            assertEquals("APPROVED", dto.decision());
            if (firstReviewId < 0) {
                firstReviewId = dto.id();
            } else {
                assertEquals(firstReviewId, dto.id());
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Integer approvedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dive_review WHERE dive_log_id = ? AND decision = 'APPROVED'",
                Integer.class, log.id());
        assertEquals(1, approvedCount);
    }

    @Test
    void appendRacingWithReviewReadsConsistentPointSet() throws Exception {
        LogDto log = newLog("cc-create-2");
        service.appendPoints(log.id(), new AppendPointsRequest("cc-up-2", validPoints()));

        List<PointInput> extra = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            // 审核后追加的勘误点，数量足够多以放大竞争窗口
            extra.add(new PointInput(T0.plus(Duration.ofMinutes(1)).plusSeconds(i), 1));
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        Future<ReviewDto> reviewFuture = pool.submit(() -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return service.review(log.id(), new ReviewRequest("cc-rv-x"));
        });
        Future<?> appendFuture = pool.submit(() -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            service.appendPoints(log.id(), new AppendPointsRequest("cc-up-3", extra));
            return null;
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();

        ReviewDto review = reviewFuture.get(30, TimeUnit.SECONDS);
        appendFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get());

        // 审核读取的点位数必须等于其版本号对应的完整点位集合，不允许读到追加了一半的中间态
        Integer pointsAtVersion = jdbc.queryForObject(
                "SELECT COUNT(*) FROM depth_point WHERE dive_log_id = ? AND log_version <= ?",
                Integer.class, log.id(), review.pointVersion());
        assertEquals(pointsAtVersion.intValue(), review.pointCount());
        assertTrue(review.pointVersion() == 1 || review.pointVersion() == 2);

        // 最终数据完整：两个版本的点位全部落库
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM depth_point WHERE dive_log_id = ?", Integer.class, log.id());
        assertEquals(209, total);
    }
}