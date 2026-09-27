package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchGuaranteeItem;
import com.example.starter.plan.web.dto.BatchGuaranteeRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 接续保障并发裁决测试：同一后车并发批量保障改签只形成一个生效时刻；
 * 同一对前后车并发登记只形成一条接续。
 */
@SpringBootTest
class ConnectionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentBatchOnSameSuccessorOnlyOneEffective() throws Exception {
        String secA = key("SECA");
        String secB = key("SECB");
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, List.of(
                new OccupancyRequest("G1", secA, at(8, 0), at(8, 30)),
                new OccupancyRequest("G1", secT, at(8, 30), at(9, 0))));
        planService.publish(pred, key("REQ"));
        createPlan(succ, List.of(
                new OccupancyRequest("G2", secT, at(9, 30), at(9, 45)),
                new OccupancyRequest("G2", secB, at(9, 45), at(10, 30))));
        planService.publish(succ, key("REQ"));
        connectionService.register(new RegisterConnectionRequest(key("REQ"), pred, succ, secT,
                15, false));
        // 前车改签延后使后车进入 AT_RISK
        String predNew = key("PREDN");
        createPlan(predNew, List.of(
                new OccupancyRequest("G1B", secA, at(9, 50), at(10, 20)),
                new OccupancyRequest("G1B", secT, at(10, 20), at(10, 50))));
        planService.reschedule(pred, new RescheduleRequest(key("REQ"), predNew, 1, 1));
        assertThat(planService.getPlan(succ).connectionRisk()).isEqualTo("AT_RISK");

        // 两个并发批次争抢同一后车
        String succNewA = key("SUCCN");
        String succNewB = key("SUCCN");
        createPlan(succNewA, List.of(
                new OccupancyRequest("G2B", secT, at(11, 5), at(11, 20)),
                new OccupancyRequest("G2B", secB, at(11, 20), at(12, 5))));
        createPlan(succNewB, List.of(
                new OccupancyRequest("G2C", secT, at(11, 5), at(11, 20)),
                new OccupancyRequest("G2C", secB, at(11, 20), at(12, 5))));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> batch(succ, succNewA),
                () -> batch(succ, succNewB)));

        // 恰好一个批次成功：同一后车并发改签只形成一个生效时刻
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.REJECTED).count()).isEqualTo(1);
        assertThat(planService.getPlan(succ).status()).isEqualTo("CANCELLED");
        String statusA = planService.getPlan(succNewA).status();
        String statusB = planService.getPlan(succNewB).status();
        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder("PUBLISHED", "DRAFT");
        // 仅一条改签关联、一条决策记录
        Integer linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_reschedule_link l"
                        + " JOIN rail_day_plan p ON p.id = l.predecessor_plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, succ);
        assertThat(linkCount).isEqualTo(1);
        Integer decisionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection_decision d"
                        + " JOIN rail_day_plan p ON p.id = d.old_successor_plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, succ);
        assertThat(decisionCount).isEqualTo(1);
        // 生效计划风险已清除
        String published = statusA.equals("PUBLISHED") ? succNewA : succNewB;
        assertThat(planService.getPlan(published).connectionRisk()).isEqualTo("NONE");
    }

    @Test
    void concurrentRegisterSamePairOnlyOneSucceeds() throws Exception {
        String secT = key("SECT");
        String pred = key("PRED");
        String succ = key("SUCC");
        createPlan(pred, List.of(new OccupancyRequest("G1", secT, at(8, 0), at(9, 0))));
        planService.publish(pred, key("REQ"));
        createPlan(succ, List.of(new OccupancyRequest("G2", secT, at(9, 30), at(10, 0))));
        planService.publish(succ, key("REQ"));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> register(pred, succ, secT),
                () -> register(pred, succ, secT)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.REJECTED).count()).isEqualTo(1);
        Integer connCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_connection c"
                        + " JOIN rail_day_plan p ON p.id = c.predecessor_plan_id"
                        + " JOIN rail_day_plan s ON s.id = c.successor_plan_id"
                        + " WHERE p.schedule_key = ? AND s.schedule_key = ?",
                Integer.class, pred, succ);
        assertThat(connCount).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        REJECTED
    }

    private Outcome batch(String succ, String succNew) {
        try {
            connectionService.batchGuarantee(new BatchGuaranteeRequest(key("REQ"),
                    List.of(new BatchGuaranteeItem(succ, succNew, 1, 1))));
            return Outcome.OK;
        } catch (ApiException e) {
            return Outcome.REJECTED;
        }
    }

    private Outcome register(String pred, String succ, String secT) {
        try {
            connectionService.register(new RegisterConnectionRequest(key("REQ"), pred, succ, secT,
                    15, false));
            return Outcome.OK;
        } catch (ApiException e) {
            return Outcome.REJECTED;
        }
    }

    private void createPlan(String scheduleKey, List<OccupancyRequest> occupancies) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY, occupancies));
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

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
