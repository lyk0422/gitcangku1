package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.WeatherRestrictionService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
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
 * 限速重排并发裁决测试（H2 内存库）：同一计划的并发改签最多一次成功且只产生一套生效时刻；
 * 两个计划并发顺延争夺同一时隙时恰好一个成功；限速登记与发布经全局锁按事务提交顺序裁决。
 * 使用未来运营日（2099-01-01）避免触发“已开始运行”分支。
 */
@SpringBootTest
class PlanRearrangeConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2099, 1, 1);

    @Autowired
    private PlanService planService;

    @Autowired
    private WeatherRestrictionService restrictionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentRescheduleWithRestrictionOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        createDraft(oldKey, section, 8, 9);
        planService.publish(oldKey, key("REQ"));
        registerRestriction(section, 9, 15, 10, 15, 120);
        String newA = key("SCH");
        String newB = key("SCH");
        createDraft(newA, section, 9, 10);
        createDraft(newB, section, 9, 10);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> rescheduleQuietly(oldKey, newA),
                () -> rescheduleQuietly(oldKey, newB)));

        // 同一旧计划的并发改签最多一次成功，不产生两套生效时刻
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        assertThat(planService.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        String statusA = planService.getPlan(newA).status();
        String statusB = planService.getPlan(newB).status();
        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder("PUBLISHED", "DRAFT");

        // 胜出新计划整体顺延至 10:20-11:20，且重排记录恰好一条
        String winner = "PUBLISHED".equals(statusA) ? newA : newB;
        assertThat(planService.getPlan(winner).occupancies().get(0).startUtc())
                .isEqualTo(at(10, 20));
        Integer recordCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrangement WHERE schedule_key IN (?, ?)",
                Integer.class, newA, newB);
        assertThat(recordCount).isEqualTo(1);
    }

    @Test
    void concurrentPublishesShiftingIntoSameSlotOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        registerRestriction(section, 8, 30, 9, 30, 100);
        String planA = key("SCH");
        String planB = key("SCH");
        createDraft(planA, section, 8, 9);
        createDraft(planB, section, 8, 9);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishQuietly(planA),
                () -> publishQuietly(planB)));

        // 两个计划都需顺延至 09:30-10:30，恰好一个发布成功，另一个 422 整体回滚
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        String statusA = planService.getPlan(planA).status();
        String statusB = planService.getPlan(planB).status();
        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder("PUBLISHED", "DRAFT");

        // 生效时隙只有一套顺延后的时刻，重排记录恰好一条
        assertThat(planService.getPublishedSlots(DAY, section)).hasSize(1);
        assertThat(planService.getPublishedSlots(DAY, section).get(0).startUtc())
                .isEqualTo(at(9, 30));
        Integer recordCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrangement WHERE schedule_key IN (?, ?)",
                Integer.class, planA, planB);
        assertThat(recordCount).isEqualTo(1);
    }

    @Test
    void concurrentRegisterAndPublishAreSerializedInCommitOrder() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, section, 8, 9);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    restrictionService.register(new RegisterRestrictionRequest(key("REQ"), section,
                            at(8, 0), at(12, 0), 100, "op"));
                    return Outcome.OK;
                },
                () -> publishQuietly(scheduleKey)));

        // 登记与发布都成功；发布结果取决于事务提交顺序，但任一顺序下状态都必须一致
        assertThat(outcomes).containsOnly(Outcome.OK);
        var plan = planService.getPlan(scheduleKey);
        assertThat(plan.status()).isEqualTo("PUBLISHED");
        Integer recordCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrangement WHERE schedule_key = ?",
                Integer.class, scheduleKey);
        if (plan.occupancies().get(0).startUtc().equals(at(8, 0))) {
            // 发布先提交：未看到限速令，不顺延、无重排记录
            assertThat(recordCount).isZero();
        } else {
            // 登记先提交：整体顺延至 12:00-13:00 并固化重排记录
            assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(12, 0));
            assertThat(recordCount).isEqualTo(1);
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome rescheduleQuietly(String oldKey, String newKey) {
        try {
            planService.reschedule(oldKey, new RescheduleRequest(key("REQ"), newKey, 1, 1));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isIn(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY);
            return Outcome.CONFLICT;
        }
    }

    private Outcome publishQuietly(String scheduleKey) {
        try {
            planService.publish(scheduleKey, key("REQ"));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            return Outcome.CONFLICT;
        }
    }

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour, 0), at(endHour, 0)))));
    }

    private void registerRestriction(String section, int startHour, int startMinute,
                                     int endHour, int endMinute, int speed) {
        restrictionService.register(new RegisterRestrictionRequest(key("REQ"), section,
                at(startHour, startMinute), at(endHour, endMinute), speed, "op"));
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
