package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.starter.plan.repo.HazardConfigRepository;
import com.example.starter.plan.service.HazardConfigService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConsistCarRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.HazardRuleRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.ReplaceHazardRulesRequest;
import com.example.starter.plan.web.dto.UpdateConsistRequest;
import java.time.Clock;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 危险品隔离的并发裁决、可控时钟时间精度与幂等指纹边界测试（真实 H2 内存库）。
 */
@SpringBootTest
class HazardConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);

    @Autowired
    private PlanService service;

    @Autowired
    private HazardConfigService configService;

    @Autowired
    private HazardConfigRepository configRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    // ---------- 同计划并发发布 ----------

    @Test
    void concurrentPublishSamePlanSameRequestKeyBothReturnFirstResult() throws Exception {
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"));
        String requestKey = key("REQ");

        List<Outcome<?>> outcomes = runConcurrently(List.of(
                safe(() -> service.publish(scheduleKey, requestKey, "op-1")),
                safe(() -> service.publish(scheduleKey, requestKey, "op-1"))));

        assertThat(outcomes).allMatch(Outcome::ok);
        assertThat(outcomes.get(0).value).isEqualTo(outcomes.get(1).value);
        assertThat(((PlanResponse) outcomes.get(0).value).status()).isEqualTo("PUBLISHED");
        // 同计划并发发布最多一次落库：快照仅一条、计划仅发布一次
        Long snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_publish_snapshot s JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE p.schedule_key = ?", Long.class, scheduleKey);
        assertThat(snapshots).isEqualTo(1L);
        assertThat(service.getPlan(scheduleKey).version()).isEqualTo(1);
    }

    @Test
    void concurrentPublishSamePlanDifferentRequestKeysOnlyOneSucceeds() throws Exception {
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"));

        List<Outcome<?>> outcomes = runConcurrently(List.of(
                safe(() -> service.publish(scheduleKey, key("REQ"), "op-1")),
                safe(() -> service.publish(scheduleKey, key("REQ"), "op-2"))));

        long okCount = outcomes.stream().filter(Outcome::ok).count();
        long conflictCount = outcomes.stream()
                .filter(o -> !o.ok() && o.error().status() == HttpStatus.CONFLICT).count();
        assertThat(okCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);
        Long snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_publish_snapshot s JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE p.schedule_key = ?", Long.class, scheduleKey);
        assertThat(snapshots).isEqualTo(1L);
    }

    // ---------- 配置与发布按提交顺序裁决 ----------

    @Test
    void concurrentRuleReplaceAndPublishAdjudicatedByCommitOrder() throws Exception {
        String a = "A" + UUID.randomUUID().toString().substring(0, 6).toUpperCase().replace("-", "");
        String b = "B" + UUID.randomUUID().toString().substring(0, 6).toUpperCase().replace("-", "");
        int v1 = installRules(a, b, 2);

        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"));
        service.replaceConsist(scheduleKey, new UpdateConsistRequest(key("REQ"), 1,
                List.of(car(1, a), car(3, b))));

        List<Outcome<?>> outcomes = runConcurrently(List.of(
                safe(() -> service.publish(scheduleKey, key("REQ"), "op-1")),
                safe(() -> configService.replaceRules(new ReplaceHazardRulesRequest(
                        key("REQ"), v1, "cfg-op",
                        List.of(new HazardRuleRequest(a, b, 5)))))));

        long publishOk = outcomes.stream()
                .filter(o -> o.ok() && o.value() instanceof PlanResponse).count();
        PublishSnapshotView snapshotAfter;
        if (publishOk == 1) {
            // 发布先提交：快照固化宽松规则版本 v1，之后配置替换不改写快照
            snapshotAfter = service.getSnapshot(scheduleKey);
            assertThat(snapshotAfter.ruleVersion()).isEqualTo(v1);
            assertThat(service.getPlan(scheduleKey).status()).isEqualTo("PUBLISHED");
            // 配置随后提交为 v2
            assertThat(configService.getCurrentRules().version()).isEqualTo(v1 + 1);
            PublishSnapshotView again = service.getSnapshot(scheduleKey);
            assertThat(again).isEqualTo(snapshotAfter);
        } else {
            // 严格规则先提交：发布 422，计划仍草稿，无快照
            assertThat(service.getPlan(scheduleKey).status()).isEqualTo("DRAFT");
            assertThat(outcomes.get(0).error().status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            Long snapshots = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rail_publish_snapshot s JOIN rail_day_plan p ON p.id = s.plan_id"
                            + " WHERE p.schedule_key = ?", Long.class, scheduleKey);
            assertThat(snapshots).isZero();
            // 扩大间隔后可发布，快照引用新版本 v2
            service.replaceConsist(scheduleKey, new UpdateConsistRequest(key("REQ"), 2,
                    List.of(car(1, a), car(6, b))));
            service.publish(scheduleKey, key("REQ"), "op-2");
            snapshotAfter = service.getSnapshot(scheduleKey);
            assertThat(snapshotAfter.ruleVersion()).isEqualTo(v1 + 1);
        }
    }

    // ---------- 可控时钟：快照时间精度毫秒且不可变 ----------

    @Test
    @SuppressWarnings("unchecked")
    void fixedClockSnapshotTimestampIsMillisPreciseAndImmutableAfterCancel() {
        long t1 = 1_760_000_000_123L;
        long t2 = t1 + 3_600_000L;
        Mockito.reset(clock);
        when(clock.millis()).thenReturn(t1);

        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"));
        PlanResponse published = service.publish(scheduleKey, key("REQ"), "op-clock");
        assertThat(published.status()).isEqualTo("PUBLISHED");

        PublishSnapshotView snapshot = service.getSnapshot(scheduleKey);
        assertThat(snapshot.createdAt()).isEqualTo(Instant.ofEpochMilli(t1));
        assertThat(snapshot.createdAt().getNano() % 1_000_000).isZero();
        Long dbCreatedAt = jdbc.queryForObject(
                "SELECT s.created_at FROM rail_publish_snapshot s JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE p.schedule_key = ?", Long.class, scheduleKey);
        assertThat(dbCreatedAt).isEqualTo(t1);

        // 时钟前进后取消，历史快照时间与内容不变（取消不回写旧证据）
        when(clock.millis()).thenReturn(t2);
        service.cancel(scheduleKey, key("REQ"));
        PublishSnapshotView afterCancel = service.getSnapshot(scheduleKey);
        assertThat(afterCancel).isEqualTo(snapshot);
        assertThat(afterCancel.createdAt()).isEqualTo(Instant.ofEpochMilli(t1));
        assertThat(service.getPlan(scheduleKey).status()).isEqualTo("CANCELLED");
    }

    // ---------- 幂等指纹：集合规范化与操作者敏感 ----------

    @Test
    void consistFingerprintOrderInsensitiveButDifferentOperatorOnPublishConflicts() {
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"));
        String requestKey = key("REQ");
        List<ConsistCarRequest> cars = List.of(car(2, null), car(1, "HZ1"), car(3, "HZ2"));
        List<ConsistCarRequest> reordered = List.of(car(1, "HZ1"), car(3, "HZ2"), car(2, null));

        PlanResponse first = service.replaceConsist(scheduleKey,
                new UpdateConsistRequest(requestKey, 1, cars));
        // 同一规范化集合（仅登记顺序不同）+ 同键重放：返回首次完整响应，版本不再增加
        PlanResponse replay = service.replaceConsist(scheduleKey,
                new UpdateConsistRequest(requestKey, 1, reordered));
        assertThat(replay).isEqualTo(first);
        assertThat(service.getPlan(scheduleKey).version()).isEqualTo(2);

        // 发布同键但操作者不同 → 409（操作者是影响快照的字段，必须参与指纹）
        String publishKey = key("PUB");
        service.publish(scheduleKey, publishKey, "op-a");
        try {
            service.publish(scheduleKey, publishKey, "op-b");
            org.assertj.core.api.Assertions.fail("同键异操作者应返回 409");
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("IDEMPOTENT_KEY_REUSED");
        }
    }

    // ---------- 失败事务不留部分结果（SQL 层核对） ----------

    @Test
    void failedBatchRescheduleLeavesNoLinksOrSnapshots() throws Exception {
        String a = "C" + UUID.randomUUID().toString().substring(0, 6).toUpperCase().replace("-", "");
        int ruleVersion = installRules(a, a + "X", 9);
        String section = key("SEC");

        String old1 = key("SCH");
        String old2 = key("SCH");
        createDraft(old1, section);
        createDraftOnHour(old2, section, 9, 10);
        service.publish(old1, key("REQ"), "op");
        service.publish(old2, key("REQ"), "op");
        // 新计划时隙相邻不重叠；new1 无危险品车厢，new2 危险品间隔不足
        String new1 = key("SCH");
        String new2 = key("SCH");
        createDraft(new1, section);
        service.replaceConsist(new1, new UpdateConsistRequest(key("REQ"), 1,
                List.of(car(1, null))));
        createDraftOnHour(new2, section, 10, 11);
        // 间隔必然不足 → 整批 422
        service.replaceConsist(new2, new UpdateConsistRequest(key("REQ"), 1,
                List.of(car(1, a), car(2, a + "X"))));

        try {
            service.batchReschedule(new com.example.starter.plan.web.dto.BatchRescheduleRequest(
                    key("REQ"), "op", List.of(
                            new com.example.starter.plan.web.dto.BatchRescheduleItemRequest(
                                    old1, new1, 1, 2),
                            new com.example.starter.plan.web.dto.BatchRescheduleItemRequest(
                                    old2, new2, 1, 2))));
            org.assertj.core.api.Assertions.fail("含非法计划的批量改签应 422");
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("HAZARD_CONFLICT");
        }

        // SQL 层核对：无关联、新计划无快照、四个计划状态全部维持
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rail_plan_reschedule_link", Long.class))
                .isZero();
        Long newSnapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_publish_snapshot s JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE p.schedule_key IN (?, ?)", Long.class, new1, new2);
        assertThat(newSnapshots).isZero();
        assertThat(service.getPlan(old1).status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(old2).status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(new1).status()).isEqualTo("DRAFT");
        assertThat(service.getPlan(new2).status()).isEqualTo("DRAFT");
        // 规则版本未因失败事务变化
        assertThat(configRepo.currentRuleVersion()).isEqualTo(ruleVersion);
    }

    // ---------- 辅助 ----------

    private record Outcome<T>(boolean ok, T value, ApiException error) {
    }

    private <T> Callable<Outcome<T>> safe(Callable<T> callable) {
        return () -> {
            try {
                return new Outcome<>(true, callable.call(), null);
            } catch (ApiException e) {
                return new Outcome<>(false, null, e);
            }
        };
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Outcome<?>> runConcurrently(
            List<? extends Callable<? extends Outcome<?>>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future> futures = new ArrayList<>();
        for (Callable<? extends Outcome<?>> t : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return t.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<Outcome<?>> outcomes = new ArrayList<>();
        for (Future f : futures) {
            outcomes.add((Outcome<?>) f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private int installRules(String a, String b, int minSeparation) {
        int current = configService.getCurrentRules().version();
        return configService.replaceRules(new ReplaceHazardRulesRequest(key("REQ"), current,
                "cfg-op", List.of(new HazardRuleRequest(a, b, minSeparation)))).version();
    }

    private void createDraft(String scheduleKey, String section) {
        createDraftOnHour(scheduleKey, section, 8, 9);
    }

    private void createDraftOnHour(String scheduleKey, String section, int startHour, int endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        DAY.atTime(startHour, 0).atZone(SH).toInstant(),
                        DAY.atTime(endHour, 0).atZone(SH).toInstant()))));
    }

    private static ConsistCarRequest car(int position, String hazardClass) {
        return new ConsistCarRequest(position, hazardClass);
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
