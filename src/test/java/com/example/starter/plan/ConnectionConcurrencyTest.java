package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConnectionView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.GuaranteeRescheduleRequest;
import com.example.starter.plan.web.dto.GuaranteeRescheduleResponse;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RegisterConnectionRequest;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 接续保障并发裁决测试：同一后车并发批量保障改签只形成一个生效时刻；
 * 同键并发登记只落一条接续；登记/改签/批量保障经全局发布锁按提交顺序裁决。
 */
@SpringBootTest
class ConnectionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final Instant T0 = Instant.parse("2026-09-21T00:00:00Z");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private PlanService planService;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentBatchRescheduleSameSuccessorOnlyOneEffective() throws Exception {
        String[] st = stations();
        String succ = key("SCH");
        setupAtRisk(st[0] + '-' + st[1], st[1] + '-' + st[2], succ);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        connectionService.batchGuaranteeReschedule(new GuaranteeRescheduleRequest(
                                key("REQ"), List.of(
                                new GuaranteeRescheduleRequest.Item(succ, 20))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("CONNECTION_NOT_AT_RISK");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        connectionService.batchGuaranteeReschedule(new GuaranteeRescheduleRequest(
                                key("REQ"), List.of(
                                new GuaranteeRescheduleRequest.Item(succ, 30))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("CONNECTION_NOT_AT_RISK");
                        return Outcome.CONFLICT;
                    }
                }));

        // 同一后车并发改签只成功一次：一个生效时刻，版本只加一
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        var plan = planService.getPlan(succ);
        assertThat(plan.version()).isEqualTo(2);
        assertThat(plan.occupancies().get(0).startUtc())
                .isIn(at(11, 20), at(11, 30));
        assertThat(connectionService.diagnose(succ).riskStatus()).isEqualTo("NONE");
        Integer decisions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_guarantee_decision d"
                        + " JOIN rail_day_plan p ON p.id = d.successor_plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, succ);
        assertThat(decisions).isEqualTo(1);
    }

    @Test
    void concurrentRegisterSameKeyReturnsFirstResult() throws Exception {
        String[] st = stations();
        String pred = key("SCH");
        String succ = key("SCH");
        createPublished(pred, st[0] + '-' + st[1], at(8), at(10));
        createPublished(succ, st[1] + '-' + st[2], at(11), at(12));
        String requestKey = key("REQ");
        String connKey = key("CONN");
        int threads = 4;

        List<Callable<ConnectionView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> connectionService.register(new RegisterConnectionRequest(
                    requestKey, connKey, pred, succ, 30, false)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ConnectionView>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<ConnectionView> views = new ArrayList<>();
        for (Future<ConnectionView> f : futures) {
            views.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，库里只有一条接续
        assertThat(views).allMatch(v -> v.equals(views.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection WHERE connection_key = ?",
                Integer.class, connKey);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentRescheduleAndBatchSerializeInCommitOrder() throws Exception {
        String[] st = stations();
        String sectionAB = st[0] + '-' + st[1];
        String sectionBC = st[1] + '-' + st[2];
        String pred = key("SCH");
        String succ = key("SCH");
        createPublished(pred, sectionAB, at(8), at(10));
        createPublished(succ, sectionBC, at(11), at(12));
        connectionService.register(new RegisterConnectionRequest(
                key("REQ"), key("CONN"), pred, succ, 30, false));
        String predNew = key("SCH");
        createDraft(predNew, sectionAB, at(8), at(10, 45));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    planService.reschedule(pred, new RescheduleRequest(key("REQ"), predNew, 1, 1));
                    return Outcome.OK;
                },
                () -> {
                    try {
                        connectionService.batchGuaranteeReschedule(new GuaranteeRescheduleRequest(
                                key("REQ"), List.of(
                                new GuaranteeRescheduleRequest.Item(succ, 20))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 批量先裁决时风险标记尚不存在 → 409
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("CONNECTION_NOT_AT_RISK");
                        return Outcome.CONFLICT;
                    }
                }));

        // 改签必须成功；批量保障按提交顺序裁决，两种终态必居其一且自洽
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        var plan = planService.getPlan(succ);
        if (outcomes.get(1) == Outcome.OK) {
            // 改签先提交：批量看到风险标记并生效
            assertThat(plan.version()).isEqualTo(2);
            assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(11, 20));
            assertThat(connectionService.diagnose(succ).riskStatus()).isEqualTo("NONE");
        } else {
            // 批量先提交：409 不占键不改状态，改签后风险标记仍在
            assertThat(plan.version()).isEqualTo(1);
            assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(11, 0));
            assertThat(connectionService.diagnose(succ).riskStatus())
                    .isEqualTo("CONNECTION_AT_RISK");
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    /**
     * 构造风险后车：前车 08:00-10:00、后车 11:00-12:00 登记保障 30 分钟，
     * 前车改签延至 10:45 终到，后车被标记 CONNECTION_AT_RISK。
     */
    private void setupAtRisk(String sectionAB, String sectionBC, String succ) {
        String pred = key("SCH");
        createPublished(pred, sectionAB, at(8), at(10));
        createPublished(succ, sectionBC, at(11), at(12));
        connectionService.register(new RegisterConnectionRequest(
                key("REQ"), key("CONN"), pred, succ, 30, false));
        String predNew = key("SCH");
        createDraft(predNew, sectionAB, at(8), at(10, 45));
        planService.reschedule(pred, new RescheduleRequest(key("REQ"), predNew, 1, 1));
        assertThat(connectionService.diagnose(succ).riskStatus())
                .isEqualTo("CONNECTION_AT_RISK");
    }

    private void createPublished(String scheduleKey, String section, Instant start, Instant end) {
        createDraft(scheduleKey, section, start, end);
        planService.publish(scheduleKey, key("REQ"));
    }

    private void createDraft(String scheduleKey, String section, Instant start, Instant end) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section, start, end))));
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

    /** 生成唯一的三站编码，保证区段站点连续且跨用例不冲突。 */
    private static String[] stations() {
        String rand = UUID.randomUUID().toString().substring(0, 8);
        return new String[]{"SA" + rand, "SB" + rand, "SC" + rand};
    }

    private static Instant at(int hour) {
        return at(hour, 0);
    }

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}