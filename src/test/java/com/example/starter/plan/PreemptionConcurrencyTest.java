package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PreemptionRecordView;
import com.example.starter.plan.web.dto.PublishedSlotView;
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
 * 抢占并发与幂等竞态测试（真实 H2 数据库，MySQL 兼容模式）：
 * 两个高等级草稿并发抢占同一低等级计划最多一个成功；抢占与取消按事务提交顺序裁决；
 * 同键并发抢占幂等重放。最终状态均通过数据库查询断言。
 */
@SpringBootTest
class PreemptionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;

    @Test
    void concurrentPreemptSameLowPlanOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        service.registerSectionPriority(section, 1);
        String low = key("SCH");
        createDraft(low, List.of(occ("G1", section, 8, 9)));
        service.publish(low, key("REQ"), null);

        // 两个高等级草稿（等级 5）同时抢占同一低等级计划
        String sectionHigh = key("SEC");
        service.registerSectionPriority(sectionHigh, 5);
        String first = key("SCH");
        String second = key("SCH");
        createDraft(first, List.of(occ("G2", section, 8, 9), occ("G3", sectionHigh, 8, 9)));
        createDraft(second, List.of(occ("G4", section, 8, 9), occ("G5", sectionHigh, 8, 9)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishOutcome(first),
                () -> publishOutcome(second)));

        // 最多一个成功，另一个必须失败并返回 409（同一时隙只能被抢占一次）
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.ALREADY_PREEMPTED).count())
                .isEqualTo(1);

        // 最终状态：低等级计划被抢占且只被抢占一次，时隙只属于一张已发布计划
        assertThat(service.getPlan(low).status()).isEqualTo("PREEMPTED");
        List<PublishedSlotView> slots = service.getPublishedSlots(DAY, section);
        assertThat(slots).hasSize(1);
        String winner = slots.get(0).scheduleKey();
        assertThat(winner).isIn(first, second);
        assertThat(service.getPlan(winner).status()).isEqualTo("PUBLISHED");
        String loser = winner.equals(first) ? second : first;
        assertThat(service.getPlan(loser).status()).isEqualTo("DRAFT");
        List<PreemptionRecordView> records = service.getPreemptionRecords(DAY, section);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).preemptedScheduleKey()).isEqualTo(low);
        assertThat(records.get(0).preemptingScheduleKey()).isEqualTo(winner);
    }

    @Test
    void preemptAndCancelResolveInCommitOrder() throws Exception {
        String section = key("SEC");
        service.registerSectionPriority(section, 1);
        String low = key("SCH");
        createDraft(low, List.of(occ("G1", section, 8, 9)));
        service.publish(low, key("REQ"), null);

        String sectionHigh = key("SEC");
        service.registerSectionPriority(sectionHigh, 5);
        String draft = key("SCH");
        createDraft(draft, List.of(occ("G2", section, 8, 9), occ("G3", sectionHigh, 8, 9)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishOutcome(draft),
                () -> {
                    try {
                        service.cancel(low, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 抢占先提交时，低等级计划已为 PREEMPTED 终态，取消得 409
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("PLAN_STATE_CONFLICT");
                        return Outcome.STATE_CONFLICT;
                    }
                }));

        // 抢占发布必然成功；取消按提交顺序要么成功（先提交）要么 409（后提交）
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        String lowStatus = service.getPlan(low).status();
        List<PreemptionRecordView> records = service.getPreemptionRecords(DAY, section);
        if (outcomes.get(1) == Outcome.OK) {
            // 取消先提交：时隙已释放，草稿按普通发布生效，无抢占记录
            assertThat(lowStatus).isEqualTo("CANCELLED");
            assertThat(records).isEmpty();
        } else {
            // 抢占先提交：低等级计划为 PREEMPTED 终态且不可再取消，记录已固化
            assertThat(lowStatus).isEqualTo("PREEMPTED");
            assertThat(records).hasSize(1);
            assertThat(records.get(0).preemptedScheduleKey()).isEqualTo(low);
        }
        // 任何顺序下：草稿已发布，区段时隙只属于它一张已发布计划
        assertThat(service.getPlan(draft).status()).isEqualTo("PUBLISHED");
        List<PublishedSlotView> slots = service.getPublishedSlots(DAY, section);
        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).scheduleKey()).isEqualTo(draft);
    }

    @Test
    void concurrentPreemptSameRequestKeyReplaysIdempotently() throws Exception {
        String section = key("SEC");
        service.registerSectionPriority(section, 1);
        String low = key("SCH");
        createDraft(low, List.of(occ("G1", section, 8, 9)));
        service.publish(low, key("REQ"), null);

        String sectionHigh = key("SEC");
        service.registerSectionPriority(sectionHigh, 5);
        String draft = key("SCH");
        createDraft(draft, List.of(occ("G2", section, 8, 9), occ("G3", sectionHigh, 8, 9)));

        // 同键同参并发抢占：全部返回首次结果，只产生一条抢占记录
        String requestKey = key("REQ");
        String preemptKey = key("PRE");
        int threads = 4;
        List<Callable<PlanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.publish(draft, requestKey, preemptKey));
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

        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(low).status()).isEqualTo("PREEMPTED");
        assertThat(service.getPreemptionRecords(DAY, section)).hasSize(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        ALREADY_PREEMPTED,
        STATE_CONFLICT
    }

    private Outcome publishOutcome(String scheduleKey) {
        try {
            service.publish(scheduleKey, key("REQ"), key("PRE"));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("SLOT_ALREADY_PREEMPTED");
            return Outcome.ALREADY_PREEMPTED;
        }
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
