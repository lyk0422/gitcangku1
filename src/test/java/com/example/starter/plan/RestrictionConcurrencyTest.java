package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.RestrictionService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 气象限速下的并发测试（H2 内存库）：并发改签+重排只产生一套生效时刻，
 * 限速登记与发布按事务提交顺序裁决，并发顺延发布只保留一个生效时隙。
 */
@SpringBootTest
@Import(TestClockConfig.class)
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
    void resetClock() {
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
        // 清理场景数据：各用例在干净的限速令与计划数据上执行（种子区段与发布锁保留）
        jdbc.update("DELETE FROM rail_plan_rearrangement");
        jdbc.update("DELETE FROM rail_speed_restriction");
        jdbc.update("DELETE FROM rail_plan_reschedule_link");
        jdbc.update("DELETE FROM rail_plan_occupancy");
        jdbc.update("DELETE FROM rail_day_plan");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @Test
    void concurrentRescheduleWithRestrictionYieldsSingleEffectiveSchedule() throws Exception {
        // 旧计划在限速登记前发布；登记限速后两个违例草稿并发改签同一旧计划
        String oldKey = key("SCH");
        createDraft(oldKey, "SEC-L1-A", 8, 9);
        planService.publish(oldKey, key("REQ"));
        register("SEC-L1-A", 0, 12, 80);
        String newA = key("SCH");
        String newB = key("SCH");
        createDraft(newA, "SEC-L1-A", 8, 9);
        createDraft(newB, "SEC-L1-A", 8, 9);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> reschedule(oldKey, newA),
                () -> reschedule(oldKey, newB)));

        // 恰好一个改签成功：同一计划不得产生两套生效时刻
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        assertThat(planService.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        PlanResponse a = planService.getPlan(newA);
        PlanResponse b = planService.getPlan(newB);
        // 赢家整体顺延至 12:00-13:00 并发布；输家保持草稿且时刻不变
        if ("PUBLISHED".equals(a.status())) {
            assertThat(a.occupancies().get(0).startUtc()).isEqualTo(at(12));
            assertThat(b.status()).isEqualTo("DRAFT");
            assertThat(b.occupancies().get(0).startUtc()).isEqualTo(at(8));
        } else {
            assertThat(b.status()).isEqualTo("PUBLISHED");
            assertThat(b.occupancies().get(0).startUtc()).isEqualTo(at(12));
            assertThat(a.status()).isEqualTo("DRAFT");
            assertThat(a.occupancies().get(0).startUtc()).isEqualTo(at(8));
        }
        // 全库仅一条重排记录（赢家）
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrangement WHERE schedule_key IN (?, ?)",
                Integer.class, newA, newB);
        assertThat(records).isEqualTo(1);
    }

    @Test
    void concurrentPublishWithRestrictionKeepsSingleEffectiveSlot() throws Exception {
        register("SEC-L1-A", 0, 12, 80);
        // 两个违例草稿都会顺延至 12:00-13:00，互相冲突
        String planA = key("SCH");
        String planB = key("SCH");
        createDraft(planA, "SEC-L1-A", 8, 9);
        createDraft(planB, "SEC-L1-A", 8, 9);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publish(planA),
                () -> publish(planB)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        // 区段上最终只有一个生效时隙
        List<PublishedSlotView> slots = planService.getPublishedSlots(DAY, "SEC-L1-A");
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).startUtc()).isEqualTo(at(12));
    }

    @Test
    void registerAndPublishSerializeInCommitOrder() throws Exception {
        // 违例草稿：登记先提交则顺延，发布先提交则按原时刻发布；两种结果都合法但必须一致
        String plan = key("SCH");
        createDraft(plan, "SEC-L1-A", 8, 9);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    register("SEC-L1-A", 0, 12, 80);
                    return Outcome.OK;
                },
                () -> publish(plan)));

        assertThat(outcomes).containsOnly(Outcome.OK);
        PlanResponse published = planService.getPlan(plan);
        assertThat(published.status()).isEqualTo("PUBLISHED");
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrangement WHERE schedule_key = ?",
                Integer.class, plan);
        if (published.occupancies().get(0).startUtc().equals(at(8))) {
            // 发布先提交：无重排记录
            assertThat(records).isEqualTo(0);
        } else {
            // 登记先提交：整体顺延至 12:00 且固化重排记录
            assertThat(published.occupancies().get(0).startUtc()).isEqualTo(at(12));
            assertThat(records).isEqualTo(1);
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
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

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour), at(endHour)))));
    }

    private void register(String section, int startHour, int endHour, int maxSpeed) {
        restrictionService.register(new RegisterRestrictionRequest(key("REQ"), key("RST"),
                section, at(startHour), at(endHour), maxSpeed, "dispatcher-1"));
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
        List<Outcome> outcomes = new java.util.ArrayList<>();
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
