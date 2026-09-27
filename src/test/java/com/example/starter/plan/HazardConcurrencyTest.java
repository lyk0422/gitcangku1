package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.HazardService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchRescheduleItem;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.CarRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.HazardConfigRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.UpdateCarsRequest;
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
 * 危险品能力并发与竞态测试：同计划并发发布最多一次成功、
 * 同键并发编组修改幂等、批量改签与第三方发布按时隙互斥。
 */
@SpringBootTest
class HazardConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;

    @Autowired
    private HazardService hazardService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSamePlanAtMostOneSucceeds() throws Exception {
        String categoryA = cat();
        String categoryB = cat();
        hazardService.replaceConfig(new HazardConfigRequest(key("REQ"), "op-cfg",
                List.of(new com.example.starter.plan.web.dto.SeparationRuleRequest(
                        categoryA, categoryB, 2)),
                List.of()));
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"), 8, 9);
        service.replaceCars(scheduleKey, new UpdateCarsRequest(key("REQ"), "op-1", 1,
                List.of(new CarRequest(0, categoryA), new CarRequest(3, categoryB))));

        int threads = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    service.publish(scheduleKey, key("REQ"), "op-pub");
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(service.getPlan(scheduleKey).status()).isEqualTo("PUBLISHED");
        // 快照恰好一份
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_publish_snapshot s"
                        + " JOIN rail_day_plan p ON p.id = s.plan_id WHERE p.schedule_key = ?",
                Integer.class, scheduleKey);
        assertThat(snapshots).isEqualTo(1);
    }

    @Test
    void concurrentReplaceCarsSameKeyReturnsFirstResult() throws Exception {
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"), 8, 9);
        String requestKey = key("REQ");
        UpdateCarsRequest request = new UpdateCarsRequest(requestKey, "op-1", 1,
                List.of(new CarRequest(0, cat()), new CarRequest(1, cat())));

        int threads = 4;
        List<Callable<PlanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.replaceCars(scheduleKey, request));
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

        // 全部返回首次结果，版本只前进一次
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(service.getPlan(scheduleKey).version()).isEqualTo(2);
        assertThat(service.getPlan(scheduleKey).cars()).hasSize(2);
    }

    @Test
    void concurrentBatchRescheduleAndThirdPartyPublishCompeteForSlot() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"), null);
        // 批量改签新草稿与第三方草稿争夺同一时隙 10:00-11:00
        String newKey = key("SCH");
        String thirdParty = key("SCH");
        createDraft(newKey, section, 10, 11);
        createDraft(thirdParty, section, 10, 11);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.batchReschedule(new BatchRescheduleRequest(key("REQ"), "op-batch",
                                List.of(new BatchRescheduleItem(oldKey, newKey, 1, 1))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.publish(thirdParty, key("REQ"), null);
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰好一方成功：另一方整次不生效
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        if (outcomes.get(0) == Outcome.OK) {
            assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(service.getPlan(newKey).status()).isEqualTo("PUBLISHED");
            assertThat(service.getPlan(thirdParty).status()).isEqualTo("DRAFT");
        } else {
            assertThat(service.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
            assertThat(service.getPlan(newKey).status()).isEqualTo("DRAFT");
            assertThat(service.getPlan(thirdParty).status()).isEqualTo("PUBLISHED");
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour), at(endHour)))));
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

    private static String cat() {
        return "C" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
