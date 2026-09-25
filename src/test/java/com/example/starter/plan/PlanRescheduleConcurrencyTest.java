package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
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
 * 改签并发裁决测试：同一旧计划的并发改签最多一次成功；
 * 改签与第三方发布竞争同一时隙时按事务提交顺序裁决，第三方抢不到改签后已占用的时隙。
 */
@SpringBootTest
class PlanRescheduleConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentRescheduleSameOldPlanOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        String newA = key("SCH");
        String newB = key("SCH");
        createDraft(newA, section, 9, 10);
        createDraft(newB, section, 9, 10);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.reschedule(oldKey, new RescheduleRequest(key("REQ"), newA, 1, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.reschedule(oldKey, new RescheduleRequest(key("REQ"), newB, 1, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        // 同一旧计划的两次改签最多一次成功
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 旧计划只取消一次，恰好一个后继发布、另一个仍为草稿，关联仅一条
        assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        String statusA = service.getPlan(newA).status();
        String statusB = service.getPlan(newB).status();
        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder("PUBLISHED", "DRAFT");
        Integer linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_reschedule_link l"
                        + " JOIN rail_day_plan p ON p.id = l.predecessor_plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, oldKey);
        assertThat(linkCount).isEqualTo(1);

        // 链完整有序且长度为 2
        RescheduleChainResponse chain = service.getRescheduleChain(oldKey);
        assertThat(chain.chain()).hasSize(2);
        assertThat(chain.chain().get(0).scheduleKey()).isEqualTo(oldKey);
        assertThat(chain.chain().get(0).status()).isEqualTo("CANCELLED");
        assertThat(chain.chain().get(1).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void concurrentRescheduleAndThirdPartyPublishRaceForSlot() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        // 改签新草稿与第三方草稿争夺同一时隙 10:00-11:00
        String newKey = key("SCH");
        String thirdParty = key("SCH");
        createDraft(newKey, section, 10, 11);
        createDraft(thirdParty, section, 10, 11);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.reschedule(oldKey, new RescheduleRequest(key("REQ"), newKey, 1, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.publish(thirdParty, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰好一方成功：第三方抢不到改签后已占用的时隙，反之改签整体回滚
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        String newStatus = service.getPlan(newKey).status();
        String thirdStatus = service.getPlan(thirdParty).status();
        if (outcomes.get(0) == Outcome.OK) {
            // 改签胜出：旧取消、新发布、第三方仍草稿
            assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(newStatus).isEqualTo("PUBLISHED");
            assertThat(thirdStatus).isEqualTo("DRAFT");
            assertThat(service.getRescheduleChain(oldKey).chain()).hasSize(2);
        } else {
            // 第三方先发布：改签 422 回滚，旧计划仍发布、新草稿仍草稿、无关联
            assertThat(service.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
            assertThat(newStatus).isEqualTo("DRAFT");
            assertThat(thirdStatus).isEqualTo("PUBLISHED");
            assertThat(service.getRescheduleChain(oldKey).chain()).hasSize(1);
        }

        // 最终该区段 10:00-11:00 只有一张计划的时隙生效
        assertThat(service.getPublishedSlots(DAY, section)
                .stream().filter(s -> s.startUtc().equals(at(10))).count())
                .isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                null, null, null,
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

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
