package com.example.starter.race.service;

import com.example.starter.race.api.ClaimRecordRequest;
import com.example.starter.race.api.CourseRecordResponse;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RecordHistoryResponse;
import com.example.starter.race.api.RegisterCourseRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 赛道纪录认定的真实并发 H2 测试：同一赛道并发认定按事务提交顺序裁决，
 * 基于过期当前纪录的申请被 422 拒绝而非覆盖；同一 recordClaimKey 并发只认定一次。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class CourseRecordConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String COURSE = "course-race";

    @Autowired
    private RaceService raceService;

    @BeforeEach
    void registerDefaultCourse() {
        raceService.registerCourse(new RegisterCourseRequest(COURSE, "req-course"));
    }

    private void seedSealedRace(String raceId, String bib, long timeMs) {
        raceService.createRace(new CreateRaceRequest(raceId, COURSE, "req-create-" + raceId));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest(bib, timeMs, 1, "req-reg-" + raceId));
        raceService.sealRace(raceId, new SealRaceRequest(2, "req-seal-" + raceId));
    }

    @Test
    void 并发认定按提交顺序裁决且基于过期纪录者422() throws Exception {
        // 既有纪录 1000ms；两个不同赛事分别产生 900ms 与 950ms 的认定申请并发提交
        seedSealedRace("race-0", "a", 1000L);
        seedSealedRace("race-1", "b", 900L);
        seedSealedRace("race-2", "c", 950L);
        raceService.claimRecord(COURSE,
                new ClaimRecordRequest("claim-0", "race-0", "a", "req-claim-0"));

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        runClaim("claim-1", "race-1", "b", success, rejected);
                        return null;
                    }),
                    pool.submit(() -> {
                        start.await();
                        runClaim("claim-2", "race-2", "c", success, rejected);
                        return null;
                    }));
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 至少先提交者成功；若 950ms 先提交，900ms 仍严格更优也会成功
        assertThat(success.get()).isBetween(1, 2);
        assertThat(success.get() + rejected.get()).isEqualTo(2);

        // 最终状态确定：当前纪录必为 900ms，历史链严格递减且以既有 1000ms 开头
        CourseRecordResponse current = raceService.getCurrentRecord(COURSE);
        assertThat(current.timeMs()).isEqualTo(900L);
        assertThat(current.raceId()).isEqualTo("race-1");
        RecordHistoryResponse history = raceService.getRecordHistory(COURSE);
        assertThat(history.records()).hasSize(1 + success.get());
        List<Long> chainTimes = history.records().stream()
                .map(CourseRecordResponse::timeMs)
                .toList();
        assertThat(chainTimes).startsWith(1000L);
        // 历史链沿认定先后严格递减（每次切换都必须严格更优）
        for (int i = 1; i < chainTimes.size(); i++) {
            assertThat(chainTimes.get(i)).isLessThan(chainTimes.get(i - 1));
        }
        assertThat(history.records().getLast()).isEqualTo(current);
    }

    @Test
    void 并发认定均不优于当前纪录时全部422且纪录不被覆盖() throws Exception {
        // 既有纪录 900ms；并发申请 950ms 与 1000ms 都必须失败
        seedSealedRace("race-0", "a", 900L);
        seedSealedRace("race-1", "b", 950L);
        seedSealedRace("race-2", "c", 1000L);
        raceService.claimRecord(COURSE,
                new ClaimRecordRequest("claim-0", "race-0", "a", "req-claim-0"));

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = List.of(
                    pool.submit(() -> {
                        start.await();
                        runClaim("claim-1", "race-1", "b", success, rejected);
                        return null;
                    }),
                    pool.submit(() -> {
                        start.await();
                        runClaim("claim-2", "race-2", "c", success, rejected);
                        return null;
                    }));
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isZero();
        assertThat(rejected.get()).isEqualTo(2);
        assertThat(raceService.getCurrentRecord(COURSE).timeMs()).isEqualTo(900L);
        assertThat(raceService.getRecordHistory(COURSE).records()).hasSize(1);
    }

    @Test
    void 同一recordClaimKey并发申请只认定一次且结果一致() throws Exception {
        seedSealedRace("race-1", "a", 1000L);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        try {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        // 相同 recordClaimKey、不同 requestId 的并发重复申请
                        return raceService.claimRecord(COURSE, new ClaimRecordRequest(
                                "claim-same", "race-1", "a", "req-claim-" + i));
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<ServiceResult> future : futures) {
                ServiceResult result = future.get(30, TimeUnit.SECONDS);
                success.incrementAndGet();
                assertThat(result.status()).isEqualTo(first.status());
                CourseRecordResponse body = (CourseRecordResponse) result.body();
                CourseRecordResponse firstBody = (CourseRecordResponse) first.body();
                assertThat(body.recordId()).isEqualTo(firstBody.recordId());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(threads);
        assertThat(raceService.getRecordHistory(COURSE).records()).hasSize(1);
        assertThat(raceService.getCurrentRecord(COURSE).timeMs()).isEqualTo(1000L);
    }

    private void runClaim(String claimKey, String raceId, String bib,
            AtomicInteger success, AtomicInteger rejected) {
        try {
            raceService.claimRecord(COURSE,
                    new ClaimRecordRequest(claimKey, raceId, bib, "req-" + claimKey));
            success.incrementAndGet();
        } catch (RecordNotBetterException ex) {
            rejected.incrementAndGet();
        }
    }
}
