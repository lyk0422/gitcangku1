package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.StockChainResponse;
import com.example.starter.plan.web.dto.TurnaroundUpdateRequest;
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
 * 车底交路并发裁决测试：同车底的发布、取消与周转参数修改按事务提交顺序串行裁决，
 * 不允许同时存在两处矛盾的衔接结论。
 */
@SpringBootTest
class StockChainConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 两张草稿同时发布并入同一车底链的同一位置：先提交者成功并入，
     * 后提交者依据已提交状态裁决，必然 422，不会出现两段都生效的矛盾结论。
     */
    @Test
    void concurrentPublishIntoSameChainOnlyOneSucceeds() throws Exception {
        String stock = key("STK");
        register(stock, 30);
        String a = key("SCH");
        draft(a, stock, "BJ", "TJ", 8, 9);
        service.publish(a, key("REQ"));
        String b = key("SCH");
        String c = key("SCH");
        // B、C 时刻相同但区段不同，排除时隙冲突干扰，仅由交路衔接裁决
        draft(b, stock, "TJ", "LF", 9.5, 10.5);
        draft(c, stock, "TJ", "LF", 9.5, 10.5);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> tryPublish(b),
                () -> tryPublish(c)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 最终该车底当日只有 A 与一个胜出段已发布，落败者保持草稿
        StockChainResponse chain = service.getStockChain(stock);
        var segments = chain.days().get(0).segments();
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).scheduleKey()).isEqualTo(a);
        assertThat(List.of(service.getPlan(b).status(), service.getPlan(c).status()))
                .containsExactlyInAnyOrder("PUBLISHED", "DRAFT");
        // 唯一胜出段与 A 连续
        assertThat(segments.get(1).gapMinutes()).isEqualTo(30L);
        assertThat(segments.get(1).linked()).isTrue();
    }

    /**
     * 周转参数并发修改携带相同 expectedVersion：先提交者升版成功，后提交者 409，
     * 参数只生效一次，不出现两个新版本。
     */
    @Test
    void concurrentTurnaroundUpdateOptimisticLock() throws Exception {
        String stock = key("STK");
        register(stock, 10);
        String a = key("SCH");
        draft(a, stock, "BJ", "TJ", 8, 9);
        service.publish(a, key("REQ"));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.updateTurnaround(stock,
                                new TurnaroundUpdateRequest(key("REQ"), 1, 15));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.updateTurnaround(stock,
                                new TurnaroundUpdateRequest(key("REQ"), 1, 15));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.OK, Outcome.CONFLICT);
        assertThat(service.getStockChain(stock).stockVersion()).isEqualTo(2);
        assertThat(service.getStockChain(stock).minTurnaroundMinutes()).isEqualTo(15);
    }

    /**
     * 取消交路中间段与发布后续段并发：按提交顺序裁决，两种结局下数据均自洽。
     * C 首站为 TJ：B 取消后 A(TJ)->C(TJ) 连续；B 在时 B(LF)->C(TJ) 站点不衔接。
     * 两种提交顺序下取消时 C 都尚未发布（发布先提交必然 422），故均不产生断链记录。
     */
    @Test
    void concurrentCancelMiddleAndPublishSuccessor() throws Exception {
        String stock = key("STK");
        register(stock, 10);
        String a = key("SCH");
        String b = key("SCH");
        draft(a, stock, "BJ", "TJ", 8, 9);
        draft(b, stock, "TJ", "LF", 9.5, 10);
        service.publish(a, key("REQ"));
        service.publish(b, key("REQ"));
        String c = key("SCH");
        draft(c, stock, "TJ", "CD", 10.5, 11);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    service.cancel(b, key("REQ"));
                    return Outcome.OK;
                },
                () -> {
                    try {
                        service.publish(c, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 取消必成功；发布仅在取消先提交时成功（A->C 连续）
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        assertThat(service.getPlan(b).status()).isEqualTo("CANCELLED");
        StockChainResponse chain = service.getStockChain(stock);
        var segments = chain.days().get(0).segments();
        if (outcomes.get(1) == Outcome.OK) {
            // 取消先提交：C 并入 A 之后，链路连续
            assertThat(service.getPlan(c).status()).isEqualTo("PUBLISHED");
            assertThat(segments).extracting(s -> s.scheduleKey()).containsExactly(a, c);
            assertThat(segments.get(1).linked()).isTrue();
        } else {
            // 发布先提交：B->C 站点不衔接，发布整体回滚，C 保持草稿
            assertThat(service.getPlan(c).status()).isEqualTo("DRAFT");
            assertThat(segments).extracting(s -> s.scheduleKey()).containsExactly(a);
        }
        // 取消时 C 必未发布，不存在“中间段”语义，两种结局均无断链记录
        assertThat(chain.chainBreaks()).isEmpty();
    }

    /**
     * 改签与第三方同车底发布竞争同一后继位置：新段与第三方时刻相同、首末站相同，
     * 先提交者占位，后提交者必因站点不衔接 422，恰好一方成功。
     */
    @Test
    void concurrentRescheduleAndPublishRaceForChainSlot() throws Exception {
        String stock = key("STK");
        register(stock, 10);
        String oldA = key("SCH");
        draft(oldA, stock, "BJ", "TJ", 8, 9);
        service.publish(oldA, key("REQ"));
        // 改签新段与第三方草稿同为 09:30-10:30 TJ->LF，竞争 A 之后同一位置（区段不同排除时隙干扰）
        String a2 = key("SCH");
        String third = key("SCH");
        draft(a2, stock, "TJ", "LF", 9.5, 10.5);
        draft(third, stock, "TJ", "LF", 9.5, 10.5);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.reschedule(oldA,
                                new RescheduleRequest(key("REQ"), a2, 1, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.publish(third, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰好一方成功，不会出现两段同时占位
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        var segments = service.getStockChain(stock).days().get(0).segments();
        if (outcomes.get(0) == Outcome.OK) {
            // 改签先提交：旧 A 取消、a2 发布；第三方再发布时与 a2 同刻且站点不衔接 → 422
            assertThat(service.getPlan(oldA).status()).isEqualTo("CANCELLED");
            assertThat(service.getPlan(a2).status()).isEqualTo("PUBLISHED");
            assertThat(service.getPlan(third).status()).isEqualTo("DRAFT");
            assertThat(segments).extracting(s -> s.scheduleKey()).containsExactly(a2);
        } else {
            // 第三方先发布：改签按交路冲突回滚，旧 A 仍发布、a2 仍草稿
            assertThat(service.getPlan(oldA).status()).isEqualTo("PUBLISHED");
            assertThat(service.getPlan(a2).status()).isEqualTo("DRAFT");
            assertThat(service.getPlan(third).status()).isEqualTo("PUBLISHED");
            assertThat(segments).extracting(s -> s.scheduleKey()).containsExactly(oldA, third);
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome tryPublish(String scheduleKey) {
        try {
            service.publish(scheduleKey, key("REQ"));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("CHAIN_LINK_CONFLICT");
            return Outcome.CONFLICT;
        }
    }

    private void register(String stock, int minutes) {
        service.updateTurnaround(stock,
                new TurnaroundUpdateRequest(key("REQ"), 0, minutes));
    }

    private void draft(String scheduleKey, String stock, String origin, String destination,
                       double startHour, double endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                stock, origin, destination,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(),
                        scheduleKey + "-SEC", at(startHour), at(endHour)))));
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Callable<Outcome> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private static Instant at(double hour) {
        long seconds = Math.round(hour * 3600);
        return DAY.atStartOfDay(SH).toInstant().plusSeconds(seconds);
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
