package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PairPublishRequest;
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
 * 夜间计划对联合发布的并发裁决（H2 内存库）：
 * 两个计划对争抢同一跨零点区段最多一个成功；普通发布与联合发布互斥；
 * 失败方两张草稿均保持草稿，不存在只发布一张的终态。
 */
@SpringBootTest
class OvernightPairConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);
    private static final LocalDate NEXT_DAY = DAY.plusDays(1);

    @Autowired
    private PlanService service;

    @Test
    void concurrentPairPublishSameOvernightSectionOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String pairKeyA = key("PAIR");
        String sameA = key("SCH");
        String nextA = key("SCH");
        createNightDraft(sameA, pairKeyA, DAY, true,
                List.of(occ(section, 0, 23, 1, 2)));
        createNightDraft(nextA, pairKeyA, NEXT_DAY, false,
                List.of(occ(section, 1, 8, 1, 9)));

        String pairKeyB = key("PAIR");
        String sameB = key("SCH");
        String nextB = key("SCH");
        createNightDraft(sameB, pairKeyB, DAY, true,
                List.of(occ(section, 0, 23, 1, 5)));
        createNightDraft(nextB, pairKeyB, NEXT_DAY, false,
                List.of(occ(section, 1, 10, 1, 11)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> pairPublishOutcome(pairKeyA, sameA, nextA),
                () -> pairPublishOutcome(pairKeyB, sameB, nextB)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 失败方两张均保持草稿，不存在只发布一张
        String[] statuses = {
                service.getPlan(sameA).status(), service.getPlan(nextA).status(),
                service.getPlan(sameB).status(), service.getPlan(nextB).status()
        };
        int published = 0;
        int draft = 0;
        for (String status : statuses) {
            assertThat(status).isIn("PUBLISHED", "DRAFT");
            if ("PUBLISHED".equals(status)) {
                published++;
            } else {
                draft++;
            }
        }
        assertThat(published).isEqualTo(2);
        assertThat(draft).isEqualTo(2);

        // 当日与次日该区段各只有胜出计划对的时隙
        List<PublishedSlotView> daySlots = service.getPublishedSlots(DAY, section);
        List<PublishedSlotView> nextSlots = service.getPublishedSlots(NEXT_DAY, section);
        assertThat(daySlots).hasSize(1);
        // 次日：胜出对的跨零点裁剪部分 + 次日成员占用
        String winnerPair = outcomes.get(0) == Outcome.OK ? pairKeyA : pairKeyB;
        String winnerNext = outcomes.get(0) == Outcome.OK ? nextA : nextB;
        assertThat(nextSlots).extracting(PublishedSlotView::scheduleKey)
                .anyMatch(k -> k.equals(winnerNext));
        assertThat(winnerPair).isNotEmpty();
    }

    @Test
    void concurrentNormalPublishAndPairPublishAreMutuallyExclusive() throws Exception {
        String section = key("SEC");
        // 普通计划当日 23:30-23:59 与夜间占用 23:00-02:00 冲突
        String normalKey = key("SCH");
        createNightDraft(normalKey, null, DAY, false,
                List.of(occ(section, 0, 23, 30, 0, 23, 59)));

        String pairKey = key("PAIR");
        String sameKey = key("SCH");
        String nextKey = key("SCH");
        createNightDraft(sameKey, pairKey, DAY, true,
                List.of(occ(section, 0, 23, 1, 2)));
        createNightDraft(nextKey, pairKey, NEXT_DAY, false,
                List.of(occ(section, 1, 8, 1, 9)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.publish(normalKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> pairPublishOutcome(pairKey, sameKey, nextKey)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        assertThat(service.getPublishedSlots(DAY, section)).hasSize(1);
    }

    @Test
    void concurrentPairPublishSameRequestKeyReplaysFirstResult() throws Exception {
        String section = key("SEC");
        String pairKey = key("PAIR");
        String sameKey = key("SCH");
        String nextKey = key("SCH");
        createNightDraft(sameKey, pairKey, DAY, true,
                List.of(occ(section, 0, 22, 0, 1, 0, 30)));
        createNightDraft(nextKey, pairKey, NEXT_DAY, false,
                List.of(occ(section, 1, 7, 1, 8)));
        String requestKey = key("REQ");
        int threads = 4;

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                service.publishPair(new PairPublishRequest(requestKey, pairKey,
                        sameKey, nextKey, 1, 1));
                return 1;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        for (Future<Integer> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        }
        pool.shutdown();

        assertThat(service.getPlan(sameKey).status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(nextKey).status()).isEqualTo("PUBLISHED");
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome pairPublishOutcome(String pairKey, String sameKey, String nextKey) {
        try {
            service.publishPair(new PairPublishRequest(key("REQ"), pairKey,
                    sameKey, nextKey, 1, 1));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
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

    private void createNightDraft(String scheduleKey, String pairKey, LocalDate opDate,
                                  boolean overnight, List<OccupancyRequest> occupancies) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, opDate,
                overnight, pairKey, occupancies));
    }

    /** DAY+dayOffset 日 hour:minute（Asia/Shanghai）的 UTC 占用。 */
    private static OccupancyRequest occ(String section, int dayOffset, int startHour,
                                        int endDayOffset, int endHour) {
        return new OccupancyRequest("G-" + UUID.randomUUID(), section,
                at(dayOffset, startHour, 0), at(endDayOffset, endHour, 0));
    }

    private static OccupancyRequest occ(String section, int dayOffset, int hour, int minute,
                                        int endDayOffset, int endHour, int endMinute) {
        return new OccupancyRequest("G-" + UUID.randomUUID(), section,
                at(dayOffset, hour, minute), at(endDayOffset, endHour, endMinute));
    }

    private static Instant at(int dayOffset, int hour, int minute) {
        return DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
