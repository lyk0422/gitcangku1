package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.ProfileService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.TrainProfileRequest;
import com.example.starter.plan.web.dto.TrainProfileResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 应急制动包络并发测试：档案并发修改按版本裁决、同 requestId 并发创建不泄露唯一键异常、
 * 并发发布幂等且快照不重复、时隙互斥在带速度占用下仍然成立。
 */
@SpringBootTest
class EnvelopeConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentProfileUpdatesSameVersionOnlyOneSucceeds() throws Exception {
        String trainNo = key("TRN");
        profileService.upsertTrainProfile(trainNo,
                new TrainProfileRequest(key("RID"), "op-a", 0, bd("1"), bd("2")));

        int threads = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String delta = "2.00" + (i + 1);
            tasks.add(() -> {
                try {
                    profileService.upsertTrainProfile(trainNo, new TrainProfileRequest(
                            key("RID"), "op-a", 1, bd("1"), bd(delta)));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(threads - 1);
        // 最终版本恰好递增一次
        assertThat(profileService.getTrainProfile(trainNo).version()).isEqualTo(2);
    }

    @Test
    void concurrentCreateSameRequestIdReturnsFirstResult() throws Exception {
        String trainNo = key("TRN");
        String requestId = key("RID");
        int threads = 4;

        List<Callable<TrainProfileResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> profileService.upsertTrainProfile(trainNo,
                    new TrainProfileRequest(requestId, "op-a", 0, bd("1"), bd("2"))));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TrainProfileResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<TrainProfileResponse> responses = new ArrayList<>();
        for (Future<TrainProfileResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，库里只有一条档案
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_train_profile WHERE train_no = ?",
                Integer.class, trainNo);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentPublishSamePlanIdempotentAndSingleSnapshot() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        profileService.upsertTrainProfile(trainNo,
                new TrainProfileRequest(key("RID"), "op-a", 0, bd("1"), bd("2")));
        profileService.upsertSectionProfile(section,
                new SectionProfileRequest(key("RID"), "op-a", 0, bd("35")));
        String scheduleKey = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest(trainNo, section, at(8), at(9), bd("10")))));

        String requestKey = key("REQ");
        int threads = 4;
        List<Callable<PlanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> planService.publish(scheduleKey, requestKey));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PlanResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<PlanResponse> responses = new ArrayList<>();
        for (Future<PlanResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 同键并发发布均返回首次结果，快照恰好一份
        assertThat(responses).allMatch(r -> r.status().equals("PUBLISHED"));
        assertThat(planService.getEnvelopeSnapshots(scheduleKey)).hasSize(1);
    }

    @Test
    void concurrentPublishCompetingPlansKeepsSlotExclusion() throws Exception {
        String trainNo = key("TRN");
        String section = key("SEC");
        profileService.upsertTrainProfile(trainNo,
                new TrainProfileRequest(key("RID"), "op-a", 0, bd("1"), bd("2")));
        profileService.upsertSectionProfile(section,
                new SectionProfileRequest(key("RID"), "op-a", 0, bd("35")));
        int contenders = 3;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                    List.of(new OccupancyRequest(trainNo, section, at(8), at(9), bd("10")))));
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        planService.publish(sk, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }).toList());

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);
        assertThat(planService.getPublishedSlots(DAY, section)).hasSize(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
