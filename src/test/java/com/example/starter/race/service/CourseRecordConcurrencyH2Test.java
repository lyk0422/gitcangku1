package com.example.starter.race.service;

import com.example.starter.race.api.ClaimRecordRequest;
import com.example.starter.race.api.CourseRecordHistoryResponse;
import com.example.starter.race.api.CourseRecordResponse;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterCourseRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 赛道纪录认定的真实并发测试（H2）：同赛道两个不同赛事的认定申请并发时，
 * 只有先在事务中提交且计时确实更优者成功，另一个基于已过期当前纪录的
 * 认定必须以422失败，历史链只增长一条。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class CourseRecordConcurrencyH2Test extends AbstractRaceH2Test {

    @Autowired
    private CourseService courseService;

    @Autowired
    private RaceService raceService;

    @Test
    void 同赛道两赛事并发认定基于已过期当前纪录者422() throws Exception {
        // 先确立 5000 基准纪录；两个赛事同为 4500（均优于基准），
        // 并发认定时先提交者成功，其余基于已过期当前纪录（5000）发起，必须422
        seedSealedRace("race-base", "z", 5000L);
        courseService.claimRecord(COURSE,
                new ClaimRecordRequest("race-base", "z", "claim-base", "req-base"));
        seedSealedRace("race-a", "a", 4500L);
        seedSealedRace("race-b", "b", 4500L);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        boolean raceA = i % 2 == 0;
                        try {
                            courseService.claimRecord(COURSE, new ClaimRecordRequest(
                                    raceA ? "race-a" : "race-b",
                                    raceA ? "a" : "b",
                                    "claim-" + i, "req-claim-" + i));
                            success.incrementAndGet();
                        } catch (RecordClaimRejectedException ex) {
                            rejected.incrementAndGet();
                            // 422 必须携带实际当前纪录
                            assertThat(ex.currentRecord()).isNotNull();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 仅先提交者成功一次；其余认定时当前纪录已是4500，相等不算严格更优，全部422
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);

        CourseRecordHistoryResponse history = courseService.getRecordHistory(COURSE);
        assertThat(history.history()).hasSize(2);
        assertThat(history.history()).extracting(CourseRecordResponse::timeMs)
                .containsExactly(5000L, 4500L);
        assertThat(history.current().timeMs()).isEqualTo(4500L);
        assertThat(history.current().seq()).isEqualTo(2);
    }

    @Test
    void 并发认定与顺序更优认定组合时链只增长且序号连续() throws Exception {
        // 先确立 5000 纪录，再并发提交 4000（更优）与 6000（更差）
        seedSealedRace("race-base", "a", 5000L);
        courseService.claimRecord(COURSE,
                new ClaimRecordRequest("race-base", "a", "claim-base", "req-base"));
        seedSealedRace("race-better", "b", 4000L);
        seedSealedRace("race-worse", "c", 6000L);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        boolean better = i % 2 == 0;
                        try {
                            courseService.claimRecord(COURSE, new ClaimRecordRequest(
                                    better ? "race-better" : "race-worse",
                                    better ? "b" : "c",
                                    "claim-c" + i, "req-claim-c" + i));
                            success.incrementAndGet();
                        } catch (RecordClaimRejectedException ex) {
                            rejected.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 4000 先提交者成功一次，其余（含所有6000与后续4000）全部422
        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);

        CourseRecordHistoryResponse history = courseService.getRecordHistory(COURSE);
        assertThat(history.history()).hasSize(2);
        assertThat(history.history()).extracting(CourseRecordResponse::seq)
                .containsExactly(1, 2);
        assertThat(history.history()).extracting(CourseRecordResponse::timeMs)
                .containsExactly(5000L, 4000L);
        assertThat(history.current().timeMs()).isEqualTo(4000L);
    }

    @Test
    void 同recordClaimKey并发申请只认定一次且结果一致() throws Exception {
        seedSealedRace("race-same", "a", 4000L);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        // 同 claimKey 同参数，不同 requestId：走 recordClaimKey 幂等
                        return courseService.claimRecord(COURSE, new ClaimRecordRequest(
                                "race-same", "a", "claim-same", "req-same-" + i));
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            String firstRecordId = null;
            for (Future<ServiceResult> future : futures) {
                ServiceResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(201);
                String recordId = ((CourseRecordResponse) result.body()).recordId();
                if (firstRecordId == null) {
                    firstRecordId = recordId;
                } else {
                    assertThat(recordId).isEqualTo(firstRecordId);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(courseService.getRecordHistory(COURSE).history()).hasSize(1);
    }

    /** 登记默认赛道（若缺）、建赛、登记指定成绩选手并封榜。 */
    private void seedSealedRace(String raceId, String bib, long finishTimeMs) {
        if (courseRepository.findCourse(COURSE).isEmpty()) {
            courseService.registerCourse(new RegisterCourseRequest(COURSE, "req-course"));
        }
        raceService.createRace(new CreateRaceRequest(raceId, COURSE, "req-create-" + raceId));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest(bib, finishTimeMs, 1, "req-reg-" + raceId));
        raceService.sealRace(raceId, new SealRaceRequest(2, "req-seal-" + raceId));
    }
}
