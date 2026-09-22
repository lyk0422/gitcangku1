package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
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
 * 改签并发裁决测试（真实 H2 数据库、真实并发线程）：
 * 同一旧计划两次改签最多一次成功；改签与第三方发布按提交顺序互斥；
 * 改签与取消、草稿替换并发时按行锁/版本裁决；不存在只取消未发布的中间状态。
 */
@SpringBootTest
class PlanRescheduleConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;

    @Test
    void concurrentReschedulesOfSameOldPlanAtMostOneSucceeds() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        draft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        String newA = key("NEWA");
        String newB = key("NEWB");
        // 两张新草稿占用不同时隙，各自单独看都可改签成功
        draft(newA, section, 10, 11);
        draft(newB, section, 11, 12);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> tryReschedule(oldKey, 1, newA, 1),
                () -> tryReschedule(oldKey, 1, newB, 1)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 旧计划只可能被取消一次；只有一张新计划发布
        PlanResponse old = service.getPlan(oldKey);
        assertThat(old.status()).isEqualTo("CANCELLED");
        String publishedNew = service.getPlan(newA).status().equals("PUBLISHED") ? newA : newB;
        String draftNew = publishedNew.equals(newA) ? newB : newA;
        assertThat(service.getPlan(draftNew).status()).isEqualTo("DRAFT");
        assertThat(service.getPublishedSlots(DAY, section)).hasSize(1);
        assertThat(service.getPublishedSlots(DAY, section).get(0).scheduleKey())
                .isEqualTo(publishedNew);
        // 改签链恰好两环
        assertThat(service.getRescheduleChain(oldKey).plans()).hasSize(2);
    }

    @Test
    void rescheduleAndThirdPartyPublishArbitratedByCommitOrder() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        String thirdParty = key("TP");
        // 旧计划与新草稿、第三方草稿都占用同一时隙；改签校验排除旧但不排除第三方
        draft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        draft(newKey, section, 8, 9);
        draft(thirdParty, section, 8, 9);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.reschedule(new RescheduleRequest(
                                key("REQ"), oldKey, 1, newKey, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 第三方先发布则改签得 422；否则因状态/关联得 409
                        assertThat(e.status()).isIn(HttpStatus.UNPROCESSABLE_ENTITY,
                                HttpStatus.CONFLICT);
                        return e.status() == HttpStatus.UNPROCESSABLE_ENTITY
                                ? Outcome.SLOT_CONFLICT : Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.publish(thirdParty, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.SLOT_CONFLICT;
                    }
                }));

        // 恰好一方成功
        long okCount = outcomes.stream().filter(o -> o == Outcome.OK).count();
        assertThat(okCount).isEqualTo(1);

        List<PublishedSlotView> slots = service.getPublishedSlots(DAY, section);
        assertThat(slots).hasSize(1);
        if (service.getPlan(newKey).status().equals("PUBLISHED")) {
            // 改签先提交：旧取消、新生效、第三方保持草稿（422）
            assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(service.getPlan(thirdParty).status()).isEqualTo("DRAFT");
            assertThat(slots.get(0).scheduleKey()).isEqualTo(newKey);
            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.SLOT_CONFLICT);
        } else {
            // 第三方先提交：改签整体回滚，旧仍发布、新仍草稿（422）
            assertThat(service.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
            assertThat(service.getPlan(newKey).status()).isEqualTo("DRAFT");
            assertThat(slots.get(0).scheduleKey()).isEqualTo(thirdParty);
            assertThat(outcomes).containsExactlyInAnyOrder(Outcome.SLOT_CONFLICT, Outcome.OK);
        }
    }

    @Test
    void rescheduleAndCancelOfSameOldPlanAtMostOneSucceeds() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        draft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        draft(newKey, section, 10, 11);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> tryReschedule(oldKey, 1, newKey, 1),
                () -> {
                    try {
                        service.cancel(oldKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        // 取消先提交则新计划保持草稿；改签先提交则新计划发布
        PlanResponse end = service.getPlan(oldKey);
        assertThat(end.status()).isEqualTo("CANCELLED");
        if (service.getPlan(newKey).status().equals("PUBLISHED")) {
            assertThat(service.getRescheduleChain(oldKey).plans()).hasSize(2);
        } else {
            assertThat(service.getPlan(newKey).status()).isEqualTo("DRAFT");
            assertThat(service.getRescheduleChain(oldKey).plans()).hasSize(1);
        }
    }

    @Test
    void rescheduleAndDraftReplaceConflictByVersion() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        draft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        draft(newKey, section, 10, 11);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> tryReschedule(oldKey, 1, newKey, 1),
                () -> {
                    try {
                        service.replaceOccupancies(newKey, new UpdateOccupanciesRequest(
                                key("REQ"), 1, List.of(occ(section, 12, 13))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        // 改签先提交：替换因新计划已发布得状态冲突；替换先提交：改签因版本 1≠2 冲突
        long okCount = outcomes.stream().filter(o -> o == Outcome.OK).count();
        assertThat(okCount).isEqualTo(1);
        PlanResponse newPlan = service.getPlan(newKey);
        if (newPlan.status().equals("PUBLISHED")) {
            assertThat(newPlan.version()).isEqualTo(1);
            assertThat(newPlan.occupancies().get(0).startUtc()).isEqualTo(at(10));
        } else {
            assertThat(newPlan.status()).isEqualTo("DRAFT");
            assertThat(newPlan.version()).isEqualTo(2);
            assertThat(newPlan.occupancies().get(0).startUtc()).isEqualTo(at(12));
            assertThat(service.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void concurrentSameKeySameParamsRescheduleAllReturnFirstResult() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        draft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        draft(newKey, section, 10, 11);
        String requestKey = key("REQ");
        int threads = 4;

        List<Callable<PlanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.reschedule(new RescheduleRequest(
                    requestKey, oldKey, 1, newKey, 1)));
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
        assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        assertThat(service.getPlan(newKey).status()).isEqualTo("PUBLISHED");
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT,
        SLOT_CONFLICT
    }

    private Outcome tryReschedule(String oldKey, int oldVersion, String newKey, int newVersion) {
        try {
            service.reschedule(new RescheduleRequest(
                    key("REQ"), oldKey, oldVersion, newKey, newVersion));
            return Outcome.OK;
        } catch (ApiException e) {
            // 失败方可能因状态（旧已被取消）或关联冲突得到 409
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
    }

    private void draft(String scheduleKey, String section, int startHour, int endHour) {
        List<OccupancyRequest> occupancies = List.of(occ(section, startHour, endHour));
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

    private static OccupancyRequest occ(String section, int startHour, int endHour) {
        return new OccupancyRequest("G-" + UUID.randomUUID(), section,
                at(startHour), at(endHour));
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
