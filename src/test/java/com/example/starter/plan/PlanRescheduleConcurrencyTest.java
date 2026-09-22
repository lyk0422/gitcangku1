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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 改签并发裁决测试（真实 H2 数据库，真实线程与事务）：
 * 同一旧计划两次改签最多一次成功；改签与第三方发布按提交顺序互斥；
 * 改签与取消/草稿替换按行锁提交顺序裁决；外部观察不到只取消未发布的中间状态。
 */
@SpringBootTest
class PlanRescheduleConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentReschedulesOfSameOldPlanOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newA = key("NEW");
        String newB = key("NEW");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newA, section, 9, 10);
        createDraft(newB, section, 10, 11);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> attemptReschedule(oldKey, newA),
                () -> attemptReschedule(oldKey, newB)));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.CONFLICT);

        // 旧计划只有一条后继关联，链上恰好两张计划
        Integer links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_succession s JOIN rail_day_plan p"
                        + " ON p.id = s.predecessor_plan_id WHERE p.schedule_key = ?",
                Integer.class, oldKey);
        assertThat(links).isEqualTo(1);
        PlanResponse old = service.getPlan(oldKey);
        assertThat(old.status()).isEqualTo("CANCELLED");
        boolean aPublished = service.getPlan(newA).status().equals("PUBLISHED");
        boolean bPublished = service.getPlan(newB).status().equals("PUBLISHED");
        assertThat(aPublished ^ bPublished).isTrue();
    }

    @Test
    void rescheduleAndThirdPartyPublishArbitratedByCommitOrder() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        String thirdParty = key("TP");
        // 旧计划占 08-09；新草稿占 08-10；第三方抢 09-10（旧不占用、改签后归新草稿）
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, section, 8, 10);
        createDraft(thirdParty, section, 9, 10);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.reschedule(new RescheduleRequest(
                                key("REQ"), oldKey, 1, newKey, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 第三方先提交时改签必须 422（旧计划占用并不能顶替第三方）
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.SLOT_CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.publish(thirdParty, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 改签先提交时第三方不得抢到该时隙
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.SLOT_CONFLICT;
                    }
                }));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.SLOT_CONFLICT);

        // 两种提交顺序都自洽：不存在“旧已取消但新仍草稿”的中间态，也不会双方同时生效
        boolean rescheduleWon = service.getPlan(newKey).status().equals("PUBLISHED");
        boolean thirdPartyWon = service.getPlan(thirdParty).status().equals("PUBLISHED");
        assertThat(rescheduleWon ^ thirdPartyWon).isTrue();
        if (rescheduleWon) {
            assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(service.getPublishedSlots(DAY, section)).singleElement()
                    .extracting(PublishedSlotView::scheduleKey).isEqualTo(newKey);
        } else {
            assertThat(service.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
            assertThat(service.getPlan(newKey).status()).isEqualTo("DRAFT");
            assertThat(service.getPublishedSlots(DAY, section))
                    .extracting(PublishedSlotView::scheduleKey)
                    .containsExactly(oldKey, thirdParty);
        }
    }

    @Test
    void rescheduleAndCancelOfOldPlanOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, section, 9, 10);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> attemptReschedule(oldKey, newKey),
                () -> {
                    try {
                        service.cancel(oldKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.CONFLICT);
        // 取消先提交时新草稿不得被连带发布
        if (service.getPlan(newKey).status().equals("DRAFT")) {
            assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(service.getPlanChain(oldKey).plans()).hasSize(1);
        }
    }

    @Test
    void rescheduleAndDraftReplaceArbitratedByCommitOrder() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, section, 9, 10);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> attemptReschedule(oldKey, newKey),
                () -> {
                    try {
                        service.replaceOccupancies(newKey, new UpdateOccupanciesRequest(
                                key("REQ"), 1, List.of(occ(section, 11, 12))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 改签先提交：草稿已发布，替换得状态冲突 409
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.CONFLICT);
        PlanResponse newPlan = service.getPlan(newKey);
        if (newPlan.status().equals("PUBLISHED")) {
            // 改签先提交：占用保持 09:00-10:00，版本仍为 1
            assertThat(newPlan.version()).isEqualTo(1);
            assertThat(newPlan.occupancies().get(0).startUtc()).isEqualTo(at(9));
        } else {
            // 替换先提交：改签因期望版本过期得 409，草稿版本 2 且占用已换
            assertThat(newPlan.version()).isEqualTo(2);
            assertThat(newPlan.occupancies().get(0).startUtc()).isEqualTo(at(11));
            assertThat(service.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void observerNeverSeesIntermediateCancelledWithoutPublishedState() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, section, 8, 9);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<ApiException> error = new AtomicReference<>();
        Future<?> rescheduleFuture = pool.submit(() -> {
            ready.countDown();
            await(go);
            try {
                service.reschedule(new RescheduleRequest(
                        key("REQ"), oldKey, 1, newKey, 1));
            } catch (ApiException e) {
                error.set(e);
            }
        });

        // 观察者在改签进行中反复查询生效时隙：只能看到旧计划或新计划，绝不允许空窗
        List<String> observedKeys = new ArrayList<>();
        Future<?> observerFuture = pool.submit(() -> {
            ready.countDown();
            await(go);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                List<PublishedSlotView> slots = service.getPublishedSlots(DAY, section);
                assertThat(slots).isNotEmpty();
                String key = slots.get(0).scheduleKey();
                if (observedKeys.isEmpty()
                        || !observedKeys.get(observedKeys.size() - 1).equals(key)) {
                    observedKeys.add(key);
                }
                if (newKey.equals(key)) {
                    return;
                }
            }
            throw new AssertionError("观察者超时未观察到改签后新计划时隙");
        });
        ready.await();
        go.countDown();
        rescheduleFuture.get(30, TimeUnit.SECONDS);
        observerFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(error.get()).isNull();
        // 读已提交隔离下观察者只能看到提交前（旧计划）或提交后（新计划）的一致状态，
        // 绝不会看到空窗或只取消未发布的中间状态
        assertThat(observedKeys).isNotEmpty();
        assertThat(observedKeys).allSatisfy(k -> assertThat(k).isIn(oldKey, newKey));
        assertThat(observedKeys).doesNotHaveDuplicates();
        assertThat(observedKeys.get(observedKeys.size() - 1)).isEqualTo(newKey);
        assertThat(service.getPublishedSlots(DAY, section)).singleElement()
                .extracting(PublishedSlotView::scheduleKey).isEqualTo(newKey);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT,
        SLOT_CONFLICT
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Outcome attemptReschedule(String oldKey, String newKey) {
        try {
            service.reschedule(new RescheduleRequest(
                    key("REQ"), oldKey, 1, newKey, 1));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT;
        }
    }

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(occ(section, startHour, endHour))));
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    await(go);
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
