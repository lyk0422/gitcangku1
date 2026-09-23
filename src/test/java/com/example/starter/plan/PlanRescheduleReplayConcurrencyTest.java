package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 原子改签并发重放与历史响应一致性回归测试（H2 内存库）。
 *
 * <p>通过外部事务持有发布锁/行锁，协调多个同键同参请求全部进入竞争窗口
 * （均已通过事务外幂等检查、阻塞在串行化锁上），再释放锁让它们依次提交，
 * 确定性复现“部分 200、部分 409 旧计划已取消”的原始缺陷场景；
 * 修复后所有请求必须返回首次成功的完整快照，且最终只产生唯一关联与唯一幂等记录。
 */
@SpringBootTest
class PlanRescheduleReplayConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final int CONCURRENCY = 6;

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    // ---------- 改签：同键同参并发重放 ----------

    @Test
    void concurrentIdenticalReschedulesAllReturnFirstSnapshot() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createDraft(oldKey, "G1", section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, "G2", section, 8, 9);

        String requestKey = key("REQ");
        RescheduleRequest request = new RescheduleRequest(requestKey, newKey, 1, 1);

        // 外部事务持有发布锁，6 个相同请求全部进入竞争窗口后再放行
        try (ExternalLock lock = ExternalLock.onPublishLock(jdbc, txManager)) {
            Race<RescheduleResponse> race = Race.start(CONCURRENCY, () -> service.reschedule(oldKey, request));
            race.awaitAllBlocked();
            lock.release();
            List<RescheduleResponse> responses = race.successes();
            List<ApiException> errors = race.errors();

            // 全部 200：没有任何一个退化为 409“旧计划已取消”
            assertThat(errors).isEmpty();
            assertThat(responses).hasSize(CONCURRENCY);
            RescheduleResponse first = responses.get(0);
            for (RescheduleResponse r : responses) {
                assertThat(r).isEqualTo(first);
            }
            // 快照内容完整：旧计划取消、新计划发布，版本与占用与首次一致
            assertThat(first.oldPlan().scheduleKey()).isEqualTo(oldKey);
            assertThat(first.oldPlan().status()).isEqualTo("CANCELLED");
            assertThat(first.oldPlan().version()).isEqualTo(1);
            assertThat(first.oldPlan().occupancies()).hasSize(1);
            assertThat(first.oldPlan().occupancies().get(0).trainNo()).isEqualTo("G1");
            assertThat(first.newPlan().scheduleKey()).isEqualTo(newKey);
            assertThat(first.newPlan().status()).isEqualTo("PUBLISHED");
            assertThat(first.newPlan().version()).isEqualTo(1);
            assertThat(first.newPlan().occupancies()).hasSize(1);
            assertThat(first.newPlan().occupancies().get(0).trainNo()).isEqualTo("G2");
        }

        // 最终唯一关系：旧取消、新发布、关联恰好一条、幂等记录恰好一条
        assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        assertThat(service.getPlan(newKey).status()).isEqualTo("PUBLISHED");
        assertThat(linkCountFrom(oldKey)).isEqualTo(1);
        assertThat(idemCount("RESCHEDULE", requestKey)).isEqualTo(1);
        assertThat(service.getRescheduleChain(oldKey).chain()).hasSize(2);
    }

    // ---------- 改签：首次成功后历史变化，重放仍返回原快照 ----------

    @Test
    void replayReturnsOriginalSnapshotAfterSuccessorRescheduledAgain() {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        String planC = key("SCH");
        createDraft(planA, "G1", section, 8, 9);
        service.publish(planA, key("REQ"));
        createDraft(planB, "G2", section, 8, 9);
        createDraft(planC, "G3", section, 8, 9);

        String requestKey = key("REQ");
        RescheduleRequest original = new RescheduleRequest(requestKey, planB, 1, 1);
        RescheduleResponse first = service.reschedule(planA, original);
        assertThat(first.newPlan().status()).isEqualTo("PUBLISHED");

        // 首次成功后将新计划继续改签（B 取消、C 发布）
        service.reschedule(planB, new RescheduleRequest(key("REQ"), planC, 1, 1));
        assertThat(service.getPlan(planB).status()).isEqualTo("CANCELLED");

        long idemBefore = totalIdemCount();
        // 重放仍返回首次快照（快照中 B 为 PUBLISHED），不复活计划、不追加关联
        RescheduleResponse replay = service.reschedule(planA, original);
        assertThat(replay).isEqualTo(first);
        assertThat(replay.newPlan().status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(planB).status()).isEqualTo("CANCELLED");
        assertThat(service.getRescheduleChain(planA).chain()).hasSize(3);
        assertThat(linkCountFrom(planA)).isEqualTo(1);
        assertThat(linkCountFrom(planB)).isEqualTo(1);
        assertThat(idemCount("RESCHEDULE", requestKey)).isEqualTo(1);
        // 普通查询（明细/改签链/已发布时隙）不写入幂等记录
        service.getPlan(planA);
        service.getRescheduleChain(planB);
        service.getPublishedSlots(DAY, section);
        assertThat(totalIdemCount()).isEqualTo(idemBefore);
    }

    @Test
    void replayReturnsOriginalSnapshotAfterNewPlanCancelled() {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createDraft(oldKey, "G1", section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, "G2", section, 8, 9);

        String requestKey = key("REQ");
        RescheduleRequest original = new RescheduleRequest(requestKey, newKey, 1, 1);
        RescheduleResponse first = service.reschedule(oldKey, original);

        // 首次成功后取消新计划，时隙释放
        service.cancel(newKey, key("REQ"));
        assertThat(service.getPlan(newKey).status()).isEqualTo("CANCELLED");

        // 重放仍返回首次快照（新计划为 PUBLISHED），不复活、不重复追加关联
        RescheduleResponse replay = service.reschedule(oldKey, original);
        assertThat(replay).isEqualTo(first);
        assertThat(replay.newPlan().status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(newKey).status()).isEqualTo("CANCELLED");
        assertThat(linkCountFrom(oldKey)).isEqualTo(1);
        assertThat(idemCount("RESCHEDULE", requestKey)).isEqualTo(1);
    }

    // ---------- 改签：同键不同参 409，不得冒充重放 ----------

    @Test
    void sameKeyWithChangedNewPlanOrExpectedVersionReturns409() {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newA = key("SCH");
        String newB = key("SCH");
        createDraft(oldKey, "G1", section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newA, "G2", section, 8, 9);
        createDraft(newB, "G3", section, 8, 9);

        String requestKey = key("REQ");
        service.reschedule(oldKey, new RescheduleRequest(requestKey, newA, 1, 1));

        // 同键改动新计划 → 409
        ApiException changedNew = expectConflict(
                () -> service.reschedule(oldKey, new RescheduleRequest(requestKey, newB, 1, 1)));
        assertThat(changedNew.code()).isEqualTo("IDEMPOTENT_KEY_REUSED");
        // 同键改动旧计划期望版本 → 409
        ApiException changedOldVersion = expectConflict(
                () -> service.reschedule(oldKey, new RescheduleRequest(requestKey, newA, 9, 1)));
        assertThat(changedOldVersion.code()).isEqualTo("IDEMPOTENT_KEY_REUSED");
        // 同键改动新计划期望版本 → 409
        ApiException changedNewVersion = expectConflict(
                () -> service.reschedule(oldKey, new RescheduleRequest(requestKey, newA, 1, 7)));
        assertThat(changedNewVersion.code()).isEqualTo("IDEMPOTENT_KEY_REUSED");

        // 冲突请求零副作用：B 仍草稿、关联与幂等记录均唯一
        assertThat(service.getPlan(newB).status()).isEqualTo("DRAFT");
        assertThat(linkCountFrom(oldKey)).isEqualTo(1);
        assertThat(idemCount("RESCHEDULE", requestKey)).isEqualTo(1);
        assertThat(service.getRescheduleChain(oldKey).chain()).hasSize(2);
    }

    // ---------- 发布/取消/草稿替换：同键并发重放同样返回首次快照 ----------

    @Test
    void concurrentIdenticalPublishCancelAndUpdateAllReplay() throws Exception {
        // 发布：发布锁竞争窗口内 6 个同键请求全部返回首次快照
        String pubKey = key("SCH");
        createDraft(pubKey, "G1", key("SEC"), 8, 9);
        String publishKey = key("REQ");
        try (ExternalLock lock = ExternalLock.onPublishLock(jdbc, txManager)) {
            Race<PlanResponse> race = Race.start(CONCURRENCY, () -> service.publish(pubKey, publishKey));
            race.awaitAllBlocked();
            lock.release();
            assertThat(race.errors()).isEmpty();
            List<PlanResponse> responses = race.successes();
            assertThat(responses).hasSize(CONCURRENCY);
            assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
            assertThat(responses.get(0).status()).isEqualTo("PUBLISHED");
            assertThat(responses.get(0).version()).isEqualTo(1);
        }
        assertThat(idemCount("PUBLISH", publishKey)).isEqualTo(1);

        // 草稿替换：行锁竞争窗口内 6 个同键请求全部返回首次快照，版本只加一次
        String updKey = key("SCH");
        String updSection = key("SEC");
        createDraft(updKey, "G2", updSection, 8, 9);
        String updateKey = key("REQ");
        UpdateOccupanciesRequest update = new UpdateOccupanciesRequest(updateKey, 1,
                List.of(new OccupancyRequest("G2", updSection, at(9), at(10))));
        try (ExternalLock lock = ExternalLock.onPlanRow(jdbc, txManager, updKey)) {
            Race<PlanResponse> race = Race.start(CONCURRENCY,
                    () -> service.replaceOccupancies(updKey, update));
            race.awaitAllBlocked();
            lock.release();
            assertThat(race.errors()).isEmpty();
            List<PlanResponse> responses = race.successes();
            assertThat(responses).hasSize(CONCURRENCY);
            assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
            assertThat(responses.get(0).version()).isEqualTo(2);
            assertThat(responses.get(0).occupancies()).hasSize(1);
            assertThat(responses.get(0).occupancies().get(0).startUtc()).isEqualTo(at(9));
        }
        assertThat(service.getPlan(updKey).version()).isEqualTo(2);
        assertThat(idemCount("UPDATE", updateKey)).isEqualTo(1);

        // 取消：行锁竞争窗口内 6 个同键请求全部返回首次快照
        String cancelKey = key("SCH");
        createDraft(cancelKey, "G3", key("SEC"), 8, 9);
        service.publish(cancelKey, key("REQ"));
        String cancelRequestKey = key("REQ");
        try (ExternalLock lock = ExternalLock.onPlanRow(jdbc, txManager, cancelKey)) {
            Race<PlanResponse> race = Race.start(CONCURRENCY,
                    () -> service.cancel(cancelKey, cancelRequestKey));
            race.awaitAllBlocked();
            lock.release();
            assertThat(race.errors()).isEmpty();
            List<PlanResponse> responses = race.successes();
            assertThat(responses).hasSize(CONCURRENCY);
            assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
            assertThat(responses.get(0).status()).isEqualTo("CANCELLED");
        }
        assertThat(service.getPlan(cancelKey).status()).isEqualTo("CANCELLED");
        assertThat(idemCount("CANCEL", cancelRequestKey)).isEqualTo(1);
    }

    // ---------- 改签与草稿替换、取消的竞争：整次提交，恰一方生效 ----------

    @Test
    void rescheduleRacesWithDraftReplaceExactlyOneWins() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createDraft(oldKey, "G1", section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, "G2", section, 8, 9);

        Race<Object> race = Race.start(2,
                () -> service.replaceOccupancies(newKey, new UpdateOccupanciesRequest(key("REQ"), 1,
                        List.of(new OccupancyRequest("G2", section, at(9), at(10))))),
                () -> service.reschedule(oldKey, new RescheduleRequest(key("REQ"), newKey, 1, 1)));
        race.awaitQuiesced();
        assertThat(race.successes()).hasSize(1);
        assertThat(race.errors()).hasSize(1);
        ApiException loser = race.errors().get(0);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);

        PlanResponse oldPlan = service.getPlan(oldKey);
        PlanResponse newPlan = service.getPlan(newKey);
        if (race.successes().get(0) instanceof RescheduleResponse) {
            // 改签胜出：旧取消、新发布（版本仍为 1），关联唯一；草稿替换 409 且零副作用
            assertThat(oldPlan.status()).isEqualTo("CANCELLED");
            assertThat(newPlan.status()).isEqualTo("PUBLISHED");
            assertThat(newPlan.version()).isEqualTo(1);
            assertThat(linkCountFrom(oldKey)).isEqualTo(1);
        } else {
            // 草稿替换胜出：新草稿版本升至 2，改签 409 整体回滚，旧仍发布、无关联
            assertThat(oldPlan.status()).isEqualTo("PUBLISHED");
            assertThat(newPlan.status()).isEqualTo("DRAFT");
            assertThat(newPlan.version()).isEqualTo(2);
            assertThat(linkCountFrom(oldKey)).isEqualTo(0);
        }
    }

    @Test
    void rescheduleRacesWithCancelExactlyOneWins() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createDraft(oldKey, "G1", section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, "G2", section, 8, 9);

        Race<Object> race = Race.start(2,
                () -> service.cancel(oldKey, key("REQ")),
                () -> service.reschedule(oldKey, new RescheduleRequest(key("REQ"), newKey, 1, 1)));
        race.awaitQuiesced();
        assertThat(race.successes()).hasSize(1);
        assertThat(race.errors()).hasSize(1);
        ApiException loser = race.errors().get(0);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.code()).isEqualTo("PLAN_STATE_CONFLICT");

        PlanResponse oldPlan = service.getPlan(oldKey);
        PlanResponse newPlan = service.getPlan(newKey);
        if (race.successes().get(0) instanceof RescheduleResponse) {
            // 改签胜出：旧经改签取消、新发布、关联唯一；取消 409
            assertThat(oldPlan.status()).isEqualTo("CANCELLED");
            assertThat(newPlan.status()).isEqualTo("PUBLISHED");
            assertThat(linkCountFrom(oldKey)).isEqualTo(1);
        } else {
            // 取消胜出：改签 409 整体回滚，新计划保持草稿、无关联
            assertThat(oldPlan.status()).isEqualTo("CANCELLED");
            assertThat(newPlan.status()).isEqualTo("DRAFT");
            assertThat(linkCountFrom(oldKey)).isEqualTo(0);
        }
    }

    // ---------- 辅助 ----------

    private void createDraft(String scheduleKey, String trainNo, String section,
                             int startHour, int endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest(trainNo, section, at(startHour), at(endHour)))));
    }

    private ApiException expectConflict(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return e;
        }
        throw new AssertionError("预期 409 冲突，实际成功");
    }

    private int linkCountFrom(String scheduleKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_reschedule_link l"
                        + " JOIN rail_day_plan p ON p.id = l.predecessor_plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, scheduleKey);
        return count == null ? 0 : count;
    }

    private int idemCount(String opType, String requestKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = ? AND request_key = ?",
                Integer.class, opType, requestKey);
        return count == null ? 0 : count;
    }

    private long totalIdemCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Long.class);
        return count == null ? 0 : count;
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * 外部事务持有的数据库锁：用于把并发请求协调进竞争窗口（均已通过事务外幂等检查、
     * 阻塞在串行化锁上），释放后请求按锁获取顺序依次提交。
     */
    private static final class ExternalLock implements AutoCloseable {

        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;

        private ExternalLock(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                             String sql, Object... args) {
            TransactionTemplate tt = new TransactionTemplate(txManager);
            this.thread = new Thread(() -> tt.execute(status -> {
                jdbc.queryForObject(sql, Long.class, args);
                held.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("外部锁释放超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("外部锁等待被中断", e);
                }
                return null;
            }), "external-lock");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        static ExternalLock onPublishLock(JdbcTemplate jdbc, PlatformTransactionManager txManager)
                throws InterruptedException {
            ExternalLock lock = new ExternalLock(jdbc, txManager,
                    "SELECT id FROM publish_lock WHERE id = 1 FOR UPDATE");
            lock.awaitHeld();
            return lock;
        }

        static ExternalLock onPlanRow(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                      String scheduleKey) throws InterruptedException {
            ExternalLock lock = new ExternalLock(jdbc, txManager,
                    "SELECT id FROM rail_day_plan WHERE schedule_key = ? FOR UPDATE", scheduleKey);
            lock.awaitHeld();
            return lock;
        }

        private void awaitHeld() throws InterruptedException {
            assertThat(held.await(10, TimeUnit.SECONDS)).as("外部锁应在 10 秒内获取").isTrue();
        }

        void release() {
            release.countDown();
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            thread.join(10000);
        }
    }

    /**
     * 并发竞跑：所有任务经同一起点门闩同时出发；结果区分成功值与业务异常，
     * 任何非 ApiException 的异常都直接作为断言失败抛出。
     */
    private static final class Race<T> {

        private final List<FutureTask<T>> tasks;
        private final List<Thread> threads;
        private final CountDownLatch go;

        private Race(List<FutureTask<T>> tasks, List<Thread> threads, CountDownLatch go) {
            this.tasks = tasks;
            this.threads = threads;
            this.go = go;
        }

        @SafeVarargs
        static <R> Race<R> start(int ignored, Callable<R>... callables) {
            return start(List.of(callables));
        }

        static <R> Race<R> start(int concurrency, Callable<R> action) {
            List<Callable<R>> callables = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                callables.add(action);
            }
            return start(callables);
        }

        private static <R> Race<R> start(List<Callable<R>> callables) {
            CountDownLatch go = new CountDownLatch(1);
            List<FutureTask<R>> tasks = new ArrayList<>();
            List<Thread> threads = new ArrayList<>();
            for (Callable<R> callable : callables) {
                FutureTask<R> task = new FutureTask<>(() -> {
                    go.await();
                    return callable.call();
                });
                tasks.add(task);
                Thread thread = new Thread(task, "race-worker-" + threads.size());
                threads.add(thread);
                thread.start();
            }
            Race<R> race = new Race<>(tasks, threads, go);
            go.countDown();
            return race;
        }

        /**
         * 等待所有工作线程进入锁等待（非 RUNNABLE）状态，确认它们已全部进入竞争窗口。
         */
        void awaitAllBlocked() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                boolean allWaiting = threads.stream().allMatch(t -> {
                    Thread.State state = t.getState();
                    return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
                            || state == Thread.State.BLOCKED || state == Thread.State.TERMINATED;
                });
                if (allWaiting) {
                    return;
                }
                Thread.sleep(25);
            }
            throw new AssertionError("工作线程未在 10 秒内全部进入锁等待状态");
        }

        /**
         * 等待所有工作线程结束（无外部锁协调的两方竞跑场景）。
         */
        void awaitQuiesced() throws InterruptedException {
            for (Thread thread : threads) {
                thread.join(30000);
            }
            for (Thread thread : threads) {
                assertThat(thread.isAlive()).as("工作线程应在 30 秒内结束").isFalse();
            }
        }

        List<T> successes() throws Exception {
            List<T> result = new ArrayList<>();
            for (FutureTask<T> task : tasks) {
                try {
                    T value = task.get(30, TimeUnit.SECONDS);
                    if (value != null) {
                        result.add(value);
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    if (!(e.getCause() instanceof ApiException)) {
                        throw e;
                    }
                }
            }
            return result;
        }

        List<ApiException> errors() throws Exception {
            List<ApiException> result = new ArrayList<>();
            for (FutureTask<T> task : tasks) {
                try {
                    task.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof ApiException apiException) {
                        result.add(apiException);
                    } else {
                        throw e;
                    }
                }
            }
            return result;
        }
    }
}
