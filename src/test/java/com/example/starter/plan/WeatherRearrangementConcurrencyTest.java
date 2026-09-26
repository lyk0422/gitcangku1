package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.WeatherRestrictionService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
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
 * 气象限速重排并发裁决测试（H2 内存库）：并发发布竞争同一顺延目标时隙最多一方成功；
 * 限速登记与发布按事务提交顺序裁决；同一旧计划的并发改签加重排不产生两套生效时刻。
 * 运营日取未来日期 2030-01-05（Asia/Shanghai），避免触碰"已开始运行"分支。
 */
@SpringBootTest
class WeatherRearrangementConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2030, 1, 5);

    @Autowired
    private PlanService planService;

    @Autowired
    private WeatherRestrictionService restrictionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishesShiftingToSameSlotOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        registerRestriction(section, at(10), at(12), 60);
        String planA = key("SCH");
        String planB = key("SCH");
        // 两个草稿均为 09:00-11:00，违反限速后都将顺延至 13:00-15:00
        createDraft(planA, section, at(9), at(11));
        createDraft(planB, section, at(9), at(11));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publish(planA),
                () -> publish(planB)));

        // 顺延目标时隙相同，恰好一方发布成功，另一方 422 时隙冲突
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 最终该区段只有一个生效时隙，且只有一条重排记录
        assertThat(planService.getPublishedSlots(DAY, section)).hasSize(1);
        assertThat(planService.getPublishedSlots(DAY, section).get(0).startUtc()).isEqualTo(at(13));
        Integer recordCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrangement r"
                        + " JOIN rail_day_plan p ON p.id = r.plan_id"
                        + " WHERE p.schedule_key IN (?, ?)",
                Integer.class, planA, planB);
        assertThat(recordCount).isEqualTo(1);
    }

    @Test
    void concurrentRegisterAndPublishAreOrderedByCommit() throws Exception {
        String section = key("SEC");
        String plan = key("SCH");
        createDraft(plan, section, at(9), at(11));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    registerRestriction(section, at(10), at(12), 60);
                    return Outcome.OK;
                },
                () -> publish(plan)));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.OK);
        // 按事务提交顺序裁决：发布要么看到限速令（整体顺延并固化记录），要么看不到（原时刻发布）
        PlanResponse published = planService.getPlan(plan);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        if (published.rearrangement() != null) {
            assertThat(published.rearrangement().shiftMinutes()).isEqualTo(240);
            assertThat(published.occupancies().get(0).startUtc()).isEqualTo(at(13));
        } else {
            assertThat(published.occupancies().get(0).startUtc()).isEqualTo(at(9));
        }
        // 无论哪种顺序，生效时隙与计划占用一致，不产生两套时刻
        assertThat(planService.getPublishedSlots(DAY, section)).hasSize(1);
        assertThat(planService.getPublishedSlots(DAY, section).get(0).startUtc())
                .isEqualTo(published.occupancies().get(0).startUtc());
    }

    @Test
    void concurrentRescheduleWithRearrangementOnlyOneEffectiveSchedule() throws Exception {
        String oldSection = key("SEC");
        String newSection = key("SEC");
        registerRestriction(newSection, at(10), at(12), 60);
        String oldPlan = key("SCH");
        createDraft(oldPlan, oldSection, at(8), at(9));
        planService.publish(oldPlan, key("REQ"));
        String newA = key("SCH");
        String newB = key("SCH");
        // 两个改签新草稿均违反限速，改签成功后都将顺延至 13:00-15:00
        createDraft(newA, newSection, at(9), at(11));
        createDraft(newB, newSection, at(9), at(11));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> reschedule(oldPlan, newA),
                () -> reschedule(oldPlan, newB)));

        // 同一旧计划的并发改签最多一次成功
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 旧计划只取消一次，恰好一个新计划发布且只有一套生效时刻
        assertThat(planService.getPlan(oldPlan).status()).isEqualTo("CANCELLED");
        PlanResponse a = planService.getPlan(newA);
        PlanResponse b = planService.getPlan(newB);
        PlanResponse winner = "PUBLISHED".equals(a.status()) ? a : b;
        PlanResponse loser = "PUBLISHED".equals(a.status()) ? b : a;
        assertThat(winner.status()).isEqualTo("PUBLISHED");
        assertThat(loser.status()).isEqualTo("DRAFT");
        assertThat(winner.occupancies().get(0).startUtc()).isEqualTo(at(13));
        assertThat(winner.rearrangement()).isNotNull();
        assertThat(winner.rearrangement().opType()).isEqualTo("RESCHEDULE");
        assertThat(loser.rearrangement()).isNull();
        assertThat(planService.getPublishedSlots(DAY, newSection)).hasSize(1);
        assertThat(planService.getPublishedSlots(DAY, newSection).get(0).startUtc())
                .isEqualTo(at(13));
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void createDraft(String scheduleKey, String section, Instant start, Instant end) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section, start, end))));
    }

    private void registerRestriction(String section, Instant start, Instant end, int speed) {
        restrictionService.register(new RegisterRestrictionRequest(key("REQ"), key("RST"),
                section, start, end, speed, "op-concurrent"));
    }

    private Outcome publish(String scheduleKey) {
        try {
            planService.publish(scheduleKey, key("REQ"));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            return Outcome.CONFLICT;
        }
    }

    private Outcome reschedule(String oldKey, String newKey) {
        try {
            planService.reschedule(oldKey, new RescheduleRequest(key("REQ"), newKey, 1, 1));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
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

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
