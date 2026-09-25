package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发与幂等竞态测试：并发发布互斥、同键并发创建、更新与发布按提交顺序生效、
 * 取消不误释放其他计划时隙。
 */
@SpringBootTest
class PlanConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSameSectionOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        int contenders = 4;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            createDraft(scheduleKey, section, 8, 9);
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        service.publish(sk, key("REQ"), null);
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }).toList());

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);

        // 最终该区段只有一张计划的时隙生效
        List<PublishedSlotView> slots = service.getPublishedSlots(DAY, section);
        assertThat(slots).hasSize(1);
    }

    @Test
    void concurrentCreateSameKeyReturnsFirstResult() throws Exception {
        String scheduleKey = key("SCH");
        String requestKey = key("REQ");
        String section = key("SEC");
        int threads = 4;

        List<OccupancyRequest> occupancies = List.of(occ(section, 8, 9));
        List<Callable<PlanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.createDraft(new CreatePlanRequest(
                    requestKey, scheduleKey, DAY, occupancies)));
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

        // 全部返回首次结果，且库里只有一张计划
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_day_plan WHERE schedule_key = ?",
                Integer.class, scheduleKey);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentUpdateAndPublishApplyInCommitOrder() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, section, 8, 9);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.replaceOccupancies(scheduleKey, new UpdateOccupanciesRequest(
                                key("REQ"), 1, List.of(occ(section, 10, 11))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 发布先提交时更新应得 409 状态冲突
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    service.publish(scheduleKey, key("REQ"), null);
                    return Outcome.OK;
                }));

        // 发布必须成功；更新要么先于发布成功（版本 2），要么因已发布而 409（版本 1）
        PlanResponse plan = service.getPlan(scheduleKey);
        assertThat(plan.status()).isEqualTo("PUBLISHED");
        if (outcomes.get(0) == Outcome.OK) {
            assertThat(plan.version()).isEqualTo(2);
            assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(10));
        } else {
            assertThat(plan.version()).isEqualTo(1);
            assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(8));
        }
    }

    @Test
    void cancelDoesNotReleaseOtherPlansSlots() {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createDraft(planA, section, 8, 9);
        createDraft(planB, section, 9, 10);
        service.publish(planA, key("REQ"), null);
        service.publish(planB, key("REQ"), null);

        service.cancel(planA, key("REQ"));

        // A 的时隙释放：新计划可占用 08:00-09:00
        String intoA = key("SCH");
        createDraft(intoA, section, 8, 9);
        service.publish(intoA, key("REQ"), null);

        // B 的时隙不受影响：09:30-10:30 与 B 冲突，必须 422
        String intoB = key("SCH");
        createDraft(intoB, section, 9, 10);
        try {
            service.publish(intoB, key("REQ"), null);
            org.assertj.core.api.Assertions.fail("应抛出 422 时隙冲突");
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("SLOT_CONFLICT");
        }
        assertThat(service.getPlan(intoB).status()).isEqualTo("DRAFT");
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
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
