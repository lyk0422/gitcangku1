package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.RestrictionService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 限速并发裁决测试：并发发布同一受限区段只有一方顺延后生效；
 * 限速登记与发布按事务提交顺序裁决；并发改签与重排不产生两套生效时刻；
 * 同键并发登记返回首次结果。
 */
@SpringBootTest
class RestrictionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private RestrictionService restrictionService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setClockBeforeOperationDay() {
        clock.setInstant(Instant.parse("2026-09-21T00:00:00Z"));
    }

    @Test
    void concurrentPublishSameRestrictedSectionOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        register(key("RST"), section, 9, 10, 60);
        // 两个草稿都违反同一限速，顺延后落在同一目标窗口 10:00-11:00
        String planA = key("SCH");
        String planB = key("SCH");
        createDraft(planA, section, 8, 30, 9, 30);
        createDraft(planB, section, 8, 30, 9, 30);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishOutcome(planA),
                () -> publishOutcome(planB)));

        // 恰好一方顺延后发布成功，另一方 422 时隙冲突
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 最终该区段只有一张计划的时隙生效，且只有胜者有重排记录
        assertThat(planService.getPublishedSlots(DAY, section)).hasSize(1);
        String winner = planService.getPlan(planAWins(outcomes, planA, planB)).scheduleKey();
        Integer recordCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrange_record WHERE schedule_key IN (?, ?)",
                Integer.class, planA, planB);
        assertThat(recordCount).isEqualTo(1);
        assertThat(planService.getRearrangeRecords(winner)).hasSize(1);
    }

    @Test
    void concurrentRegisterAndPublishSerializeByCommitOrder() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createDraft(plan, section, 8, 30, 9, 30);
        String restrictionKey = key("RST");

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    register(restrictionKey, section, 9, 10, 60);
                    return Outcome.OK;
                },
                () -> publishOutcome(plan)));

        // 登记必然成功；发布按提交顺序裁决：先于登记则原时刻发布，后于登记则整体顺延
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        assertThat(outcomes.get(1)).isEqualTo(Outcome.OK);
        var published = planService.getPlan(plan);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        var records = planService.getRearrangeRecords(plan);
        if (published.occupancies().get(0).startUtc().equals(at(8, 30))) {
            // 发布先提交：未顺延、无重排记录
            assertThat(records).isEmpty();
        } else {
            // 登记先提交：整体顺延至 10:00 且固化重排记录
            assertThat(published.occupancies().get(0).startUtc()).isEqualTo(at(10, 0));
            assertThat(records).hasSize(1);
            assertThat(records.get(0).restrictionKey()).isEqualTo(restrictionKey);
        }
    }

    @Test
    void concurrentRescheduleAndRearrangeNoDuplicateEffectiveSchedules() throws Exception {
        String section = key("SEC");
        String oldSection = key("SEC");
        register(key("RST"), section, 9, 10, 60);
        String oldKey = key("SCH");
        createDraft(oldKey, oldSection, 7, 0, 8, 0);
        planService.publish(oldKey, key("REQ"));
        // 改签新草稿违反限速将顺延至 10:00-11:00，与第三方草稿争夺同一窗口
        String newKey = key("SCH");
        String thirdParty = key("SCH");
        createDraft(newKey, section, 8, 30, 9, 30);
        createDraft(thirdParty, section, 10, 0, 11, 0);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.reschedule(oldKey,
                                new RescheduleRequest(key("REQ"), newKey, 1, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> publishOutcome(thirdParty)));

        // 恰好一方成功：不产生两套生效时刻
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        if (outcomes.get(0) == Outcome.OK) {
            // 改签胜出：旧取消、新顺延后发布、第三方仍草稿、重排记录固化
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(planService.getPlan(newKey).status()).isEqualTo("PUBLISHED");
            assertThat(planService.getPlan(newKey).occupancies().get(0).startUtc())
                    .isEqualTo(at(10, 0));
            assertThat(planService.getPlan(thirdParty).status()).isEqualTo("DRAFT");
            assertThat(planService.getRearrangeRecords(newKey)).hasSize(1);
        } else {
            // 第三方先占位：改签 422 整体回滚，旧计划仍发布、新草稿原时刻、无记录
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
            assertThat(planService.getPlan(newKey).status()).isEqualTo("DRAFT");
            assertThat(planService.getPlan(newKey).occupancies().get(0).startUtc())
                    .isEqualTo(at(8, 30));
            assertThat(planService.getPlan(thirdParty).status()).isEqualTo("PUBLISHED");
            assertThat(planService.getRearrangeRecords(newKey)).isEmpty();
        }
        // 最终 10:00-11:00 窗口只有一张计划生效
        assertThat(planService.getPublishedSlots(DAY, section).stream()
                .filter(s -> s.startUtc().equals(at(10, 0))).count())
                .isEqualTo(1);
    }

    @Test
    void concurrentRegisterSameKeyReturnsFirstResult() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        String requestKey = key("REQ");
        int threads = 4;

        List<Callable<RestrictionResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> restrictionService.register(new RegisterRestrictionRequest(
                    requestKey, restrictionKey, section, at(9, 0), at(10, 0), 60, "op")));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RestrictionResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<RestrictionResponse> responses = new ArrayList<>();
        for (Future<RestrictionResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，且库里只有一条限速令
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_speed_restriction WHERE restriction_key = ?",
                Integer.class, restrictionKey);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private String planAWins(List<Outcome> outcomes, String planA, String planB) {
        return outcomes.get(0) == Outcome.OK ? planA : planB;
    }

    private Outcome publishOutcome(String scheduleKey) {
        try {
            planService.publish(scheduleKey, key("REQ"));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            return Outcome.CONFLICT;
        }
    }

    private void register(String restrictionKey, String section,
                          int startHour, int endHour, int speed) {
        restrictionService.register(new RegisterRestrictionRequest(key("REQ"), restrictionKey,
                section, at(startHour, 0), at(endHour, 0), speed, "op"));
    }

    private void createDraft(String scheduleKey, String section,
                             int startHour, int startMinute, int endHour, int endMinute) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour, startMinute), at(endHour, endMinute)))));
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

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}