package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PreemptionView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RegisterSectionRequest;
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

/**
 * 抢占并发边界测试（真实 H2 数据库）：两个高等级草稿并发抢占同一低等级计划最多一个成功；
 * 抢占与取消并发按事务提交顺序裁决，不出现原计划仍为已发布或时隙双重归属。
 */
@SpringBootTest
class PreemptionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;

    @Test
    void concurrentPreemptionOfSamePlanOnlyOneSucceeds() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        String secC = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 5);
        registerSection(secC, 5);

        String low = key("SCH");
        createDraft(low, List.of(occ("G1", secA, 8, 9)));
        service.publish(low, key("REQ"), null);

        String high1 = key("SCH");
        createDraft(high1, List.of(occ("G2", secA, 8, 9), occ("G3", secB, 8, 9)));
        String high2 = key("SCH");
        createDraft(high2, List.of(occ("G4", secA, 8, 9), occ("G5", secC, 8, 9)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishOutcome(high1),
                () -> publishOutcome(high2)));

        // 最多一个抢占成功，另一个必须 409（同一时隙只能被抢占一次）
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.ALREADY_PREEMPTED).count())
                .isEqualTo(1);

        // 低等级计划被降级为终态，且只有一条抢占记录
        assertThat(service.getPlan(low).status()).isEqualTo("PREEMPTED");
        List<PreemptionView> records = service.listPreemptions().stream()
                .filter(p -> p.preemptedScheduleKey().equals(low))
                .toList();
        assertThat(records).hasSize(1);

        // 时隙只属于胜出的已发布计划，失败方保持草稿
        String winner = records.get(0).preemptingScheduleKey();
        String loser = winner.equals(high1) ? high2 : high1;
        assertThat(service.getPlan(winner).status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(loser).status()).isEqualTo("DRAFT");
        List<PublishedSlotView> slots = service.getPublishedSlots(DAY, secA);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).scheduleKey()).isEqualTo(winner);
    }

    @Test
    void concurrentPreemptAndCancelResolveInCommitOrder() throws Exception {
        String secA = key("SEC");
        String secB = key("SEC");
        registerSection(secA, 1);
        registerSection(secB, 5);

        String low = key("SCH");
        createDraft(low, List.of(occ("G1", secA, 8, 9)));
        service.publish(low, key("REQ"), null);

        String high = key("SCH");
        createDraft(high, List.of(occ("G2", secA, 8, 9), occ("G3", secB, 8, 9)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishOutcome(high),
                () -> {
                    try {
                        service.cancel(low, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.STATE_CONFLICT;
                    }
                }));

        // 高等级草稿必然发布成功；低等级计划按提交顺序进入某个终态，绝不仍为已发布
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        assertThat(service.getPlan(high).status()).isEqualTo("PUBLISHED");
        String lowStatus = service.getPlan(low).status();
        assertThat(lowStatus).isIn("PREEMPTED", "CANCELLED");

        List<PreemptionView> records = service.listPreemptions().stream()
                .filter(p -> p.preemptedScheduleKey().equals(low))
                .toList();
        if (lowStatus.equals("PREEMPTED")) {
            // 抢占先提交：取消必须 409，且留有抢占记录
            assertThat(outcomes.get(1)).isEqualTo(Outcome.STATE_CONFLICT);
            assertThat(records).hasSize(1);
        } else {
            // 取消先提交：抢占方无冲突目标正常发布，无抢占记录
            assertThat(outcomes.get(1)).isEqualTo(Outcome.OK);
            assertThat(records).isEmpty();
        }

        // 时隙唯一归属高等级计划
        List<PublishedSlotView> slots = service.getPublishedSlots(DAY, secA);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).scheduleKey()).isEqualTo(high);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        ALREADY_PREEMPTED,
        STATE_CONFLICT
    }

    private Outcome publishOutcome(String scheduleKey) {
        try {
            service.publish(scheduleKey, key("REQ"), key("PREEMPT"));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("SLOT_ALREADY_PREEMPTED");
            return Outcome.ALREADY_PREEMPTED;
        }
    }

    private void registerSection(String sectionId, int priority) {
        service.registerSection(new RegisterSectionRequest(key("REQ"), sectionId, priority));
    }

    private void createDraft(String scheduleKey, List<OccupancyRequest> occupancies) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY, occupancies));
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

    private static OccupancyRequest occ(String trainNo, String section, int startHour, int endHour) {
        return new OccupancyRequest(trainNo, section, at(startHour), at(endHour));
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
