package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 原子改签并发重放与历史快照一致性回归（真实 H2，MODE=MySQL）。
 *
 * <p>核心手段：在放行竞争线程前，用独立连接以 {@code SELECT ... FOR UPDATE} 占住
 * 发布锁（或计划行锁），确保所有同键请求都真实排队在数据库锁上、均看不到首个事务，
 * 再一次性放闸。修复前该窗口内等待者会在首个事务提交后撞上“旧计划已取消/版本已变”
 * 而返回 409；修复后等待者必须在锁内做权威幂等复核并返回首次成功快照。
 */
@SpringBootTest
class PlanRescheduleReplayConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 题干核心缺陷：6 个同 requestKey 同参并发改签 ----------

    @Test
    void sixIdenticalConcurrentReschedulesAllReplayFirstSnapshot() throws Exception {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, section, 9, 10);
        String requestKey = key("REQ");

        List<RescheduleResponse> responses = race(6, lockPublish(), List.of(
                () -> service.reschedule(oldKey,
                        new RescheduleRequest(requestKey, newKey, 1, 1))));

        // 全部 200，且响应逐字段等于首次成功快照：旧 CANCELLED / 新 PUBLISHED，版本均为 1
        RescheduleResponse first = responses.get(0);
        assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(first));
        assertThat(first.oldPlan().scheduleKey()).isEqualTo(oldKey);
        assertThat(first.oldPlan().status()).isEqualTo("CANCELLED");
        assertThat(first.oldPlan().version()).isEqualTo(1);
        assertThat(first.oldPlan().occupancies()).hasSize(1);
        assertThat(first.oldPlan().occupancies().get(0).startUtc()).isEqualTo(at(8));
        assertThat(first.newPlan().scheduleKey()).isEqualTo(newKey);
        assertThat(first.newPlan().status()).isEqualTo("PUBLISHED");
        assertThat(first.newPlan().version()).isEqualTo(1);
        assertThat(first.newPlan().occupancies()).hasSize(1);
        assertThat(first.newPlan().occupancies().get(0).startUtc()).isEqualTo(at(9));

        // 唯一关联、唯一幂等记录，且存储快照与首次响应完全一致
        assertThat(linkCount(oldKey)).isEqualTo(1);
        Integer idemCount = idemCount(requestKey);
        assertThat(idemCount).isEqualTo(1);
        String storedJson = jdbc.queryForObject(
                "SELECT response_json FROM idempotency_record"
                        + " WHERE op_type = 'RESCHEDULE' AND request_key = ?",
                String.class, requestKey);
        assertThat(objectMapper.readTree(storedJson)).isEqualTo(toTree(first));

        // 最终状态与链
        assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        assertThat(service.getPlan(newKey).status()).isEqualTo("PUBLISHED");
        assertThat(service.getRescheduleChain(oldKey).chain()).hasSize(2);

        // 首次成功后再取消新计划，同键重放仍返回原快照（新计划快照为 PUBLISHED），不复活任何计划
        service.cancel(newKey, key("REQ"));
        assertThat(service.getPlan(newKey).status()).isEqualTo("CANCELLED");
        RescheduleResponse replayAfterCancel = service.reschedule(oldKey,
                new RescheduleRequest(requestKey, newKey, 1, 1));
        assertThat(replayAfterCancel).isEqualTo(first);
        assertThat(replayAfterCancel.newPlan().status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        assertThat(service.getPlan(newKey).status()).isEqualTo("CANCELLED");
        assertThat(linkCount(oldKey)).isEqualTo(1);
        assertThat(idemCount(requestKey)).isEqualTo(1);
    }

    // ---------- 首次成功后新计划继续改签，重放仍为原快照，不重复加关系 ----------

    @Test
    void replayStaysFrozenAfterSuccessorRescheduledAgain() {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        String thirdKey = key("THIRD");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, section, 9, 10);
        createDraft(thirdKey, section, 10, 11);

        String firstKey = key("REQ");
        RescheduleResponse first = service.reschedule(oldKey,
                new RescheduleRequest(firstKey, newKey, 1, 1));
        // 新计划继续改签给第三份草稿
        String secondKey = key("REQ");
        service.reschedule(newKey, new RescheduleRequest(secondKey, thirdKey, 1, 1));

        // 两次成功的最终事实：旧、新均取消，第三份发布，链长 3，关联恰好 2 条
        assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        assertThat(service.getPlan(newKey).status()).isEqualTo("CANCELLED");
        assertThat(service.getPlan(thirdKey).status()).isEqualTo("PUBLISHED");
        assertThat(service.getRescheduleChain(oldKey).chain()).hasSize(3);
        assertThat(linkCount(oldKey, newKey)).isEqualTo(2);

        // 原键重放仍是首次快照（新计划当时 PUBLISHED），不复活、不加版本、不追加关联
        RescheduleResponse replayFirst = service.reschedule(oldKey,
                new RescheduleRequest(firstKey, newKey, 1, 1));
        assertThat(replayFirst).isEqualTo(first);
        assertThat(replayFirst.newPlan().status()).isEqualTo("PUBLISHED");
        RescheduleResponse replaySecond = service.reschedule(newKey,
                new RescheduleRequest(secondKey, thirdKey, 1, 1));
        assertThat(replaySecond.newPlan().scheduleKey()).isEqualTo(thirdKey);
        assertThat(linkCount(oldKey, newKey)).isEqualTo(2);
        assertThat(service.getPlan(oldKey).version()).isEqualTo(1);
        assertThat(service.getPlan(newKey).version()).isEqualTo(1);
        assertThat(service.getPlan(thirdKey).version()).isEqualTo(1);
    }

    // ---------- 同键改动新计划或期望版本 → 409，不得冒充重放 ----------

    @Test
    void sameKeyDifferentParamsReturnsConflictNotReplay() {
        String section = key("SEC");
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, section, 9, 10);
        String requestKey = key("REQ");

        RescheduleResponse first = service.reschedule(oldKey,
                new RescheduleRequest(requestKey, newKey, 1, 1));

        expectConflict(() -> service.reschedule(oldKey,
                new RescheduleRequest(requestKey, key("OTHER"), 1, 1)), "IDEMPOTENT_KEY_REUSED");
        expectConflict(() -> service.reschedule(oldKey,
                new RescheduleRequest(requestKey, newKey, 2, 1)), "IDEMPOTENT_KEY_REUSED");
        expectConflict(() -> service.reschedule(oldKey,
                new RescheduleRequest(requestKey, newKey, 1, 2)), "IDEMPOTENT_KEY_REUSED");

        // 被拒参数未写入任何额外幂等记录，原键同参仍可重放
        assertThat(idemCount(requestKey)).isEqualTo(1);
        assertThat(service.reschedule(oldKey, new RescheduleRequest(requestKey, newKey, 1, 1)))
                .isEqualTo(first);
    }

    // ---------- 422 回滚：去重记录不占键，解除冲突后原失败键可修正重试 ----------

    @Test
    void slotConflictLeavesEverythingUntouchedAndKeyCanRetry() {
        String section = key("SEC");
        String oldSection = key("SEC");
        String thirdParty = key("TP");
        createDraft(thirdParty, section, 10, 11);
        service.publish(thirdParty, key("REQ"));
        String oldKey = key("OLD");
        String newKey = key("NEW");
        createDraft(oldKey, oldSection, 8, 9);
        service.publish(oldKey, key("REQ"));
        // 新草稿与第三方已发布时隙重叠
        createDraft(newKey, section, 10, 11);
        String requestKey = key("REQ");

        expectConflict(() -> service.reschedule(oldKey,
                new RescheduleRequest(requestKey, newKey, 1, 1)), "SLOT_CONFLICT");

        // 旧计划、草稿、关联、去重记录全部不变
        assertThat(service.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(newKey).status()).isEqualTo("DRAFT");
        assertThat(service.getPlan(newKey).occupancies()).hasSize(1);
        assertThat(linkCount(oldKey)).isZero();
        assertThat(idemCount(requestKey)).isZero();

        // 解除冲突（取消第三方释放时隙）后，原失败键同参重试成功，且可继续重放
        service.cancel(thirdParty, key("REQ"));
        RescheduleResponse retried = service.reschedule(oldKey,
                new RescheduleRequest(requestKey, newKey, 1, 1));
        assertThat(retried.oldPlan().status()).isEqualTo("CANCELLED");
        assertThat(retried.newPlan().status()).isEqualTo("PUBLISHED");
        assertThat(idemCount(requestKey)).isEqualTo(1);
        assertThat(service.reschedule(oldKey, new RescheduleRequest(requestKey, newKey, 1, 1)))
                .isEqualTo(retried);
    }

    // ---------- 发布锁竞争纳入核对：6 个同键并发发布全部返回首次快照 ----------

    @Test
    void sixIdenticalConcurrentPublishesAllReplayFirstSnapshot() throws Exception {
        String section = key("SEC");
        String planKey = key("PUB");
        createDraft(planKey, section, 8, 9);
        String requestKey = key("REQ");

        List<PlanResponse> responses = race(6, lockPublish(), List.of(
                () -> service.publish(planKey, requestKey)));

        PlanResponse first = responses.get(0);
        assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(first));
        assertThat(first.status()).isEqualTo("PUBLISHED");
        assertThat(first.version()).isEqualTo(1);
        assertThat(service.getPlan(planKey).status()).isEqualTo("PUBLISHED");
        assertThat(service.getPublishedSlots(DAY, section)).hasSize(1);
        assertThat(idemCount(requestKey)).isEqualTo(1);
    }

    // ---------- 行锁竞争纳入核对：6 个同键并发取消全部返回首次快照 ----------

    @Test
    void sixIdenticalConcurrentCancelsAllReplayFirstSnapshot() throws Exception {
        String section = key("SEC");
        String planKey = key("CAN");
        createDraft(planKey, section, 8, 9);
        service.publish(planKey, key("REQ"));
        String requestKey = key("REQ");

        List<PlanResponse> responses = race(6, lockPlan(planKey), List.of(
                () -> service.cancel(planKey, requestKey)));

        PlanResponse first = responses.get(0);
        assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(first));
        assertThat(first.status()).isEqualTo("CANCELLED");
        assertThat(service.getPlan(planKey).status()).isEqualTo("CANCELLED");
        assertThat(service.getPublishedSlots(DAY, section)).isEmpty();
        assertThat(idemCount(requestKey)).isEqualTo(1);
    }

    // ---------- 行锁竞争纳入核对：6 个同键并发草稿替换只加一次版本 ----------

    @Test
    void sixIdenticalConcurrentReplacementsIncrementVersionOnce() throws Exception {
        String section = key("SEC");
        String planKey = key("UPD");
        createDraft(planKey, section, 8, 9);
        String requestKey = key("REQ");
        List<OccupancyRequest> next = List.of(occ(section, 14, 15));

        List<PlanResponse> responses = race(6, lockPlan(planKey), List.of(
                () -> service.replaceOccupancies(planKey,
                        new UpdateOccupanciesRequest(requestKey, 1, next))));

        PlanResponse first = responses.get(0);
        assertThat(responses).allSatisfy(r -> assertThat(r).isEqualTo(first));
        assertThat(first.version()).isEqualTo(2);
        assertThat(first.occupancies().get(0).startUtc()).isEqualTo(at(14));
        PlanResponse stored = service.getPlan(planKey);
        assertThat(stored.version()).isEqualTo(2);
        assertThat(stored.status()).isEqualTo("DRAFT");
        assertThat(stored.occupancies()).hasSize(1);
        assertThat(idemCount(requestKey)).isEqualTo(1);
    }

    // ---------- 普通查询不写入任何去重记录，也不改变状态 ----------

    @Test
    void readOnlyQueriesDoNotWriteIdempotencyRecords() {
        String section = key("SEC");
        String planKey = key("Q");
        createDraft(planKey, section, 8, 9);

        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record", Integer.class);
        service.getPlan(planKey);
        service.getRescheduleChain(planKey);
        service.getPublishedSlots(DAY, section);
        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record", Integer.class);

        assertThat(after).isEqualTo(before);
        assertThat(service.getPlan(planKey).status()).isEqualTo("DRAFT");
    }

    // ---------- 辅助 ----------

    /**
     * 让 n 个相同任务在屏障占锁期间同时发起竞争：所有工作线程都已进入事务并阻塞在数据库锁上后
     * 再放闸，保证没有任何一个竞争线程能在窗口内看到首个事务的结果。
     *
     * <p>判定“已进入数据库竞争”不依赖线程状态（JDBC socket 等待在 JVM 中仍是 RUNNABLE），
     * 而是轮询连接池活跃连接数：屏障占 1 条，每个进入事务并在锁上阻塞的工作线程各占 1 条，
     * 达到 n+1 即说明全部竞争请求都已真实排队。
     */
    private <T> List<T> race(int n, Connection barrier, List<Callable<T>> tasks) throws Exception {
        if (tasks.size() != 1) {
            throw new IllegalArgumentException("本回归用例的 n 个任务参数完全一致，只传一个模板");
        }
        Callable<T> template = tasks.get(0);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return template.call();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        // 屏障连接(1) + 全部工作线程连接(n) 都在使用中，才放行首个事务
        awaitActiveConnections(n + 1, 20_000);

        // 放闸：提交屏障事务释放行锁，随后首个事务胜出，其余在锁内幂等复核
        barrier.commit();
        barrier.close();

        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    private void awaitActiveConnections(int expectedActive, long timeoutMillis)
            throws InterruptedException {
        com.zaxxer.hikari.HikariDataSource hds = (com.zaxxer.hikari.HikariDataSource) dataSource;
        var mx = hds.getHikariPoolMXBean();
        if (mx == null) {
            // 拿不到池指标时退化为一个有界的短暂停顿，屏障本身仍保证串行裁决
            Thread.sleep(Math.min(timeoutMillis, 500));
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            // 连续 3 次（间隔 50ms）都达标才算稳定排队：被锁阻塞的事务连接会持续占用，
            // 而事务前的短暂幂等查询连接会迅速释放，不会造成稳定的高水位。
            if (mx.getActiveConnections() >= expectedActive) {
                if (++stable >= 3) {
                    return;
                }
            } else {
                stable = 0;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待竞争工作线程进入数据库超时，当前活跃连接数="
                + mx.getActiveConnections() + " 期望>=" + expectedActive);
    }

    private Connection lockPublish() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM publish_lock WHERE id = 1 FOR UPDATE")) {
            assertThat(ps.executeQuery().next()).isTrue();
        }
        return c;
    }

    private Connection lockPlan(String scheduleKey) throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM rail_day_plan WHERE schedule_key = ? FOR UPDATE")) {
            ps.setString(1, scheduleKey);
            assertThat(ps.executeQuery().next()).isTrue();
        }
        return c;
    }

    private void expectConflict(Callable<?> action, String expectedCode) {
        try {
            action.call();
            fail("应抛出 ApiException: " + expectedCode);
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(expectedCode.equals("SLOT_CONFLICT")
                    ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo(expectedCode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int linkCount(String... predecessorKeys) {
        String placeholders = String.join(",", predecessorKeys.stream().map(k -> "?").toList());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_reschedule_link l"
                        + " JOIN rail_day_plan p ON p.id = l.predecessor_plan_id"
                        + " WHERE p.schedule_key IN (" + placeholders + ")",
                Integer.class, (Object[]) predecessorKeys);
        return count == null ? 0 : count;
    }

    private Integer idemCount(String requestKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_key = ?",
                Integer.class, requestKey);
    }

    private JsonNode toTree(Object value) {
        try {
            return objectMapper.readTree(objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(occ(section, startHour, endHour))));
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
