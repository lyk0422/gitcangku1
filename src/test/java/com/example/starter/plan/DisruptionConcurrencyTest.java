package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.DisruptionService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.DisruptionActivateRequest;
import com.example.starter.plan.web.dto.DisruptionDetailResponse;
import com.example.starter.plan.web.dto.DisruptionMappingItem;
import com.example.starter.plan.web.dto.DisruptionRegisterRequest;
import com.example.starter.plan.web.dto.DisruptionSubmitRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PublishedSlotView;
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
 * 封锁切换并发裁决测试（真实 H2 库与真实并发线程）：
 * 激活与普通发布、取消竞争时按事务提交顺序裁决，不出现部分切换；
 * 同 requestId 并发激活只产生一份链与快照。
 */
@SpringBootTest
class DisruptionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private DisruptionService disruptionService;

    @Autowired
    private PlanService planService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentActivateAndThirdPartyPublishRaceForSlot() throws Exception {
        String blockedSection = key("SEC");
        String altSection = key("SEC");
        String oldKey = key("OLD");
        createDraft(oldKey, blockedSection, 8, 9);
        planService.publish(oldKey, key("REQ"));
        // 替代草稿与第三方草稿竞争 altSection 10:00-11:00
        String replKey = key("REP");
        String thirdParty = key("OBS");
        createDraft(replKey, altSection, 10, 11);
        createDraft(thirdParty, altSection, 10, 11);

        String switchKey = key("SW");
        registerAndSubmit(switchKey, blockedSection, 7, 10, oldKey, replKey);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        disruptionService.activate(switchKey, new DisruptionActivateRequest(
                                key("REQ"), List.of(mapping(oldKey, replKey))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        planService.publish(thirdParty, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰好一方成功
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        if (outcomes.get(0) == Outcome.OK) {
            // 切换胜出：旧挂起、替代发布、第三方仍草稿，封锁区段旧时隙已释放
            assertSwitchActive(switchKey);
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("SUSPENDED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("PUBLISHED");
            assertThat(planService.getPlan(thirdParty).status()).isEqualTo("DRAFT");
            assertThat(planService.getPublishedSlots(DAY, blockedSection)).isEmpty();
        } else {
            // 第三方先发布：激活 422 整体回滚，旧仍发布、替代仍草稿，无链无快照
            assertSwitchRegistered(switchKey);
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("DRAFT");
            assertThat(planService.getPlan(thirdParty).status()).isEqualTo("PUBLISHED");
            assertNoLinksOrSnapshots(switchKey);
        }
        // altSection 10:00-11:00 最终仅一张计划时隙生效
        List<PublishedSlotView> slots = planService.getPublishedSlots(DAY, altSection);
        assertThat(slots).hasSize(1);
    }

    @Test
    void concurrentActivateAndCancelOldPlanApplyInCommitOrder() throws Exception {
        String blockedSection = key("SEC");
        String altSection = key("SEC");
        String oldKey = key("OLD");
        createDraft(oldKey, blockedSection, 8, 9);
        planService.publish(oldKey, key("REQ"));
        String replKey = key("REP");
        createDraft(replKey, altSection, 8, 9);

        String switchKey = key("SW");
        registerAndSubmit(switchKey, blockedSection, 7, 10, oldKey, replKey);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        disruptionService.activate(switchKey, new DisruptionActivateRequest(
                                key("REQ"), List.of(mapping(oldKey, replKey))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        planService.cancel(oldKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        if (outcomes.get(0) == Outcome.OK) {
            // 切换先提交：取消随后因旧计划已 SUSPENDED 失败
            assertSwitchActive(switchKey);
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("SUSPENDED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("PUBLISHED");
        } else {
            // 取消先提交：激活重新计算后影响集合为空，整体回滚
            assertSwitchRegistered(switchKey);
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("DRAFT");
            assertNoLinksOrSnapshots(switchKey);
        }
    }

    @Test
    void concurrentActivateAndRescheduleOldPlanApplyInCommitOrder() throws Exception {
        String blockedSection = key("SEC");
        String altSection = key("SEC");
        String oldKey = key("OLD");
        createDraft(oldKey, blockedSection, 8, 9);
        planService.publish(oldKey, key("REQ"));
        String replKey = key("REP");
        createDraft(replKey, altSection, 8, 9);
        // 改签用的新草稿（空闲区段，不与替代冲突）
        String rescheduleTarget = key("NEW");
        createDraft(rescheduleTarget, key("SEC"), 18, 19);

        String switchKey = key("SW");
        registerAndSubmit(switchKey, blockedSection, 7, 10, oldKey, replKey);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        disruptionService.activate(switchKey, new DisruptionActivateRequest(
                                key("REQ"), List.of(mapping(oldKey, replKey))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        planService.reschedule(oldKey,
                                new RescheduleRequest(key("REQ"), rescheduleTarget, 1, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        if (outcomes.get(0) == Outcome.OK) {
            assertSwitchActive(switchKey);
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("SUSPENDED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("PUBLISHED");
            // 改签随后失败：改签目标仍草稿，无改签链
            assertThat(planService.getPlan(rescheduleTarget).status()).isEqualTo("DRAFT");
            assertThat(planService.getRescheduleChain(oldKey).chain()).hasSize(1);
        } else {
            assertSwitchRegistered(switchKey);
            assertThat(planService.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(planService.getPlan(replKey).status()).isEqualTo("DRAFT");
            assertThat(planService.getPlan(rescheduleTarget).status()).isEqualTo("PUBLISHED");
            assertNoLinksOrSnapshots(switchKey);
        }
    }

    @Test
    void concurrentActivateSameRequestIdBothReplayFirstSnapshot() throws Exception {
        String blockedSection = key("SEC");
        String altSection = key("SEC");
        String oldKey = key("OLD");
        createDraft(oldKey, blockedSection, 8, 9);
        planService.publish(oldKey, key("REQ"));
        String replKey = key("REP");
        createDraft(replKey, altSection, 8, 9);

        String switchKey = key("SW");
        registerAndSubmit(switchKey, blockedSection, 7, 10, oldKey, replKey);
        String requestId = key("REQ");
        List<DisruptionMappingItem> mappings = List.of(mapping(oldKey, replKey));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<DisruptionDetailResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return disruptionService.activate(switchKey,
                        new DisruptionActivateRequest(requestId, mappings));
            }));
        }
        ready.await();
        go.countDown();
        List<DisruptionDetailResponse> responses = new ArrayList<>();
        for (Future<DisruptionDetailResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 所有线程返回同一份首次快照
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertSwitchActive(switchKey);
        Integer links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_replace_link r"
                        + " JOIN rail_disruption_switch d ON d.id = r.switch_id"
                        + " WHERE d.switch_key = ?", Integer.class, switchKey);
        assertThat(links).isEqualTo(1);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_snapshot s"
                        + " JOIN rail_disruption_switch d ON d.id = s.switch_id"
                        + " WHERE d.switch_key = ?", Integer.class, switchKey);
        assertThat(snapshots).isEqualTo(1);
        assertThat(planService.getPlan(oldKey).status()).isEqualTo("SUSPENDED");
        assertThat(planService.getPlan(replKey).status()).isEqualTo("PUBLISHED");
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour), at(endHour)))));
    }

    private void registerAndSubmit(String switchKey, String section, int windowStartHour,
                                   int windowEndHour, String oldKey, String replKey) {
        disruptionService.register(new DisruptionRegisterRequest(
                key("REQ"), switchKey, section, at(windowStartHour), at(windowEndHour)));
        disruptionService.submitMappings(switchKey, new DisruptionSubmitRequest(
                List.of(mapping(oldKey, replKey))));
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

    private void assertSwitchActive(String switchKey) {
        assertThat(disruptionService.getSwitch(switchKey).disruption().status())
                .isEqualTo("ACTIVE");
    }

    private void assertSwitchRegistered(String switchKey) {
        assertThat(disruptionService.getSwitch(switchKey).disruption().status())
                .isEqualTo("REGISTERED");
    }

    private void assertNoLinksOrSnapshots(String switchKey) {
        Integer links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_replace_link r"
                        + " JOIN rail_disruption_switch d ON d.id = r.switch_id"
                        + " WHERE d.switch_key = ?", Integer.class, switchKey);
        assertThat(links).isEqualTo(0);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_disruption_snapshot s"
                        + " JOIN rail_disruption_switch d ON d.id = s.switch_id"
                        + " WHERE d.switch_key = ?", Integer.class, switchKey);
        assertThat(snapshots).isEqualTo(0);
    }

    private static DisruptionMappingItem mapping(String oldKey, String replKey) {
        return new DisruptionMappingItem(oldKey, replKey);
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
