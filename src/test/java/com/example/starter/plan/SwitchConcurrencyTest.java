package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.SwitchService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.SwitchActivateRequest;
import com.example.starter.plan.web.dto.SwitchDetailResponse;
import com.example.starter.plan.web.dto.SwitchMappingItem;
import com.example.starter.plan.web.dto.SwitchRegisterRequest;
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
 * 区段封锁切换并发裁决测试（真实 H2）：同一切换单并发激活最多一次成功、无部分切换；
 * 激活与普通取消/发布按事务提交顺序裁决；同 requestKey 并发激活共享首次快照。
 */
@SpringBootTest
class SwitchConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

    @Autowired
    private SwitchService switchService;

    @Autowired
    private PlanService planService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentActivateSameSwitchOnlyOneSucceedsNoPartialSwitch() throws Exception {
        String section = key("SEC");
        String old1 = key("SCH");
        String old2 = key("SCH");
        String repl1 = key("SCH");
        String repl2A = key("SCH");
        String repl2B = key("SCH");
        createDraft(old1, section, 8, 9);
        createDraft(old2, section, 10, 11);
        planService.publish(old1, key("REQ"));
        planService.publish(old2, key("REQ"));
        createDraft(repl1, section, 8, 9);
        createDraft(repl2A, section, 10, 11);
        createDraft(repl2B, section, 10, 11);
        String switchKey = registerSwitch(section);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> tryActivate(switchKey, key("REQ"),
                        mapping(old1, repl1, 1, 1), mapping(old2, repl2A, 1, 1)),
                () -> tryActivate(switchKey, key("REQ"),
                        mapping(old1, repl1, 1, 1), mapping(old2, repl2B, 1, 1))));

        // 同一切换单并发激活恰好一次成功，另一次 409
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 无部分切换：两个旧计划均挂起，恰好一组替代发布、另一组仍草稿
        assertThat(planService.getPlan(old1).status()).isEqualTo("SUSPENDED");
        assertThat(planService.getPlan(old2).status()).isEqualTo("SUSPENDED");
        String statusA = planService.getPlan(repl2A).status();
        String statusB = planService.getPlan(repl2B).status();
        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder("PUBLISHED", "DRAFT");
        assertThat(planService.getPlan(repl1).status()).isEqualTo("PUBLISHED");

        // 切换单 ACTIVE，替代链恰好 2 条
        SwitchDetailResponse detail = switchService.getSwitch(switchKey);
        assertThat(detail.switchInfo().status()).isEqualTo("ACTIVE");
        Integer linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_replacement_link l"
                        + " JOIN rail_section_switch s ON s.id = l.switch_id"
                        + " WHERE s.switch_key = ?", Integer.class, switchKey);
        assertThat(linkCount).isEqualTo(2);

        // ACTIVE 封锁窗口内生效时隙只有替代计划，无旧占用残留
        List<String> slotOwners = planService.getPublishedSlots(DAY, section).stream()
                .map(s -> s.scheduleKey()).toList();
        assertThat(slotOwners).containsExactlyInAnyOrder(repl1,
                "PUBLISHED".equals(statusA) ? repl2A : repl2B);
        assertThat(slotOwners).doesNotContain(old1, old2);
    }

    @Test
    void concurrentActivateAndCancelOfOldPlanCommitOrderDecides() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String replKey = key("SCH");
        createDraft(oldKey, section, 8, 9);
        planService.publish(oldKey, key("REQ"));
        createDraft(replKey, section, 8, 9);
        String switchKey = registerSwitch(section);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> tryActivate(switchKey, key("REQ"), mapping(oldKey, replKey, 1, 1)),
                () -> {
                    try {
                        planService.cancel(oldKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 激活先提交：旧计划已挂起，不可取消
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰好一方成功，无部分切换
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        boolean activateWon = outcomes.get(0) == Outcome.OK;
        if (activateWon) {
            // 激活先提交：旧 SUSPENDED、替代 PUBLISHED、取消失败，窗口内时隙归替代
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("SUSPENDED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("PUBLISHED");
            assertThat(switchService.getSwitch(switchKey).switchInfo().status())
                    .isEqualTo("ACTIVE");
            assertThat(planService.getPublishedSlots(DAY, section))
                    .extracting(s -> s.scheduleKey()).containsExactly(replKey);
        } else {
            // 取消先提交：激活 409 整体回滚，旧 CANCELLED、替代仍 DRAFT、切换单仍登记
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("DRAFT");
            assertThat(switchService.getSwitch(switchKey).switchInfo().status())
                    .isEqualTo("REGISTERED");
            assertThat(planService.getPublishedSlots(DAY, section)).isEmpty();
            Integer linkCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rail_plan_replacement_link l"
                            + " JOIN rail_section_switch s ON s.id = l.switch_id"
                            + " WHERE s.switch_key = ?", Integer.class, switchKey);
            assertThat(linkCount).isEqualTo(0);
        }
    }

    @Test
    void concurrentActivateAndPublishRaceForSlotExactlyOneWins() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String replKey = key("SCH");
        String thirdParty = key("SCH");
        createDraft(oldKey, section, 8, 9);
        planService.publish(oldKey, key("REQ"));
        // 替代草稿与第三方草稿争夺窗口内 10:00-11:00 时隙（与旧计划 8-9 不重叠）
        createDraft(replKey, section, 10, 11);
        createDraft(thirdParty, section, 10, 11);
        String switchKey = registerSwitch(section);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> tryActivate(switchKey, key("REQ"), mapping(oldKey, replKey, 1, 1)),
                () -> {
                    try {
                        planService.publish(thirdParty, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰好一方成功：
        //  - 激活先提交：旧挂起、替代发布，第三方发布因 10-11 时隙被占 422 回滚；
        //  - 第三方先发布：它本身落入封锁窗口，激活因影响集合变化 409 整体回滚，
        //    旧计划仍发布（8-9 时隙保留）、替代仍草稿、切换单仍登记。
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        boolean activateWon = outcomes.get(0) == Outcome.OK;
        List<String> slotOwners = planService.getPublishedSlots(DAY, section).stream()
                .map(s -> s.scheduleKey()).toList();
        if (activateWon) {
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("SUSPENDED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("PUBLISHED");
            assertThat(planService.getPlan(thirdParty).status()).isEqualTo("DRAFT");
            assertThat(slotOwners).containsExactly(replKey);
        } else {
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("DRAFT");
            assertThat(planService.getPlan(thirdParty).status()).isEqualTo("PUBLISHED");
            assertThat(slotOwners).containsExactlyInAnyOrder(oldKey, thirdParty);
            assertThat(switchService.getSwitch(switchKey).switchInfo().status())
                    .isEqualTo("REGISTERED");
        }
    }

    @Test
    void concurrentActivateWithSameRequestKeySharesFirstSnapshot() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String replKey = key("SCH");
        createDraft(oldKey, section, 8, 9);
        planService.publish(oldKey, key("REQ"));
        createDraft(replKey, section, 8, 9);
        String switchKey = registerSwitch(section);
        String requestKey = key("REQ");

        List<SwitchDetailResponse> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SwitchDetailResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return switchService.activate(switchKey, new SwitchActivateRequest(requestKey,
                        List.of(mapping(oldKey, replKey, 1, 1))));
            }));
        }
        ready.await();
        go.countDown();
        for (Future<SwitchDetailResponse> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 两个并发同键请求都成功且返回同一份首次快照
        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(results.get(0).switchInfo().status()).isEqualTo("ACTIVE");
        assertThat(planService.getPlan(oldKey).status()).isEqualTo("SUSPENDED");
        assertThat(planService.getPlan(replKey).status()).isEqualTo("PUBLISHED");
        // 幂等记录只有一条，当前切换单替代链只有一条
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = 'SWITCH_ACTIVATE'"
                        + " AND request_key = ?", Integer.class, requestKey);
        assertThat(idemCount).isEqualTo(1);
        Integer linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_replacement_link l"
                        + " JOIN rail_section_switch s ON s.id = l.switch_id"
                        + " WHERE s.switch_key = ?", Integer.class, switchKey);
        assertThat(linkCount).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome tryActivate(String switchKey, String requestKey, SwitchMappingItem... mappings) {
        try {
            switchService.activate(switchKey,
                    new SwitchActivateRequest(requestKey, List.of(mappings)));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isIn(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY);
            return Outcome.CONFLICT;
        }
    }

    private SwitchMappingItem mapping(String oldKey, String replKey, int oldVersion,
                                      int replVersion) {
        return new SwitchMappingItem(oldKey, replKey, oldVersion, replVersion);
    }

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour), at(endHour)))));
    }

    private String registerSwitch(String section) {
        String switchKey = key("SW");
        switchService.register(new SwitchRegisterRequest(key("REQ"), switchKey, section,
                at(8), at(12)));
        return switchKey;
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
