package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchRescheduleItem;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RegisterWorkBlockRequest;
import com.example.starter.plan.web.dto.WorkBlockPlanAction;
import com.example.starter.plan.web.dto.WorkBlockResponse;
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
 * 封锁并发裁决测试（真实 H2，真实线程与事务，设超时）：
 * 封锁登记与计划发布竞争同一交叉窗口时只能一方占用；同封锁键并发登记只有一方成功；
 * 同 requestKey 并发登记全部返回首次结果；封锁登记与批量改签竞争窗口同样互斥。
 */
@SpringBootTest
class WorkBlockConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentBlockRegisterAndPublishOnlyOneOccupiesWindow() throws Exception {
        String section = key("SEC");
        String blockKey = key("BLK");
        // 已发布 P 占窗口前半 10:00-10:30，封锁登记将撤销 P；
        // 草稿 Q 占窗口后半 10:30-11:00，与 P 端点相邻不冲突，但与封锁窗口相交。
        String existing = key("SCH-P");
        createDraft(existing, section, 10, 10, 10, 30);
        service.publish(existing, key("REQ"));
        String contender = key("SCH-Q");
        createDraft(contender, section, 10, 30, 11, 0);

        RegisterWorkBlockRequest registerReq = new RegisterWorkBlockRequest(
                key("REQ"), blockKey, section, at(10), at(11),
                List.of(new WorkBlockPlanAction(existing, "CANCEL", null)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.registerWorkBlock(registerReq);
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 发布先提交：相交计划 Q 不在清理清单中 → 422
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.publish(contender, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 封锁先提交：Q 与已生效封锁相交 → 422
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰好一方占用交叉窗口
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        if (outcomes.get(0) == Outcome.OK) {
            // 封锁胜出：P 已撤销、封锁生效、Q 仍草稿，窗口内无已发布时隙
            assertThat(service.getWorkBlock(blockKey).status()).isEqualTo("EFFECTIVE");
            assertThat(service.getPlan(existing).status()).isEqualTo("CANCELLED");
            assertThat(service.getPlan(contender).status()).isEqualTo("DRAFT");
            assertThat(service.getPublishedSlots(DAY, section)
                    .stream().filter(s -> !s.startUtc().isBefore(at(10))
                            && s.endUtc().equals(at(11))).count()).isZero();
        } else {
            // 发布胜出：Q 先发布，封锁登记视 Q 为未清理计划而 422 回滚；P、Q 均仍发布
            assertThat(service.getPlan(contender).status()).isEqualTo("PUBLISHED");
            assertThat(service.getPlan(existing).status()).isEqualTo("PUBLISHED");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rail_work_block WHERE block_key = ?",
                    Integer.class, blockKey)).isEqualTo(0);
        }
    }

    @Test
    void concurrentRegisterSameBlockKeyWithDifferentRequestKeysOnlyOneSucceeds()
            throws Exception {
        String section = key("SEC");
        String blockKey = key("BLK");

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.registerWorkBlock(new RegisterWorkBlockRequest(
                                key("REQ"), blockKey, section, at(10), at(11), List.of()));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.registerWorkBlock(new RegisterWorkBlockRequest(
                                key("REQ"), blockKey, section, at(10), at(11), List.of()));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_block WHERE block_key = ?",
                Integer.class, blockKey);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentRegisterSameRequestKeyReturnsFirstResult() throws Exception {
        String section = key("SEC");
        String blockKey = key("BLK");
        String requestKey = key("REQ");
        int threads = 4;

        List<Callable<WorkBlockResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.registerWorkBlock(new RegisterWorkBlockRequest(
                    requestKey, blockKey, section, at(13), at(14), List.of())));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<WorkBlockResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<WorkBlockResponse> responses = new ArrayList<>();
        for (Future<WorkBlockResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_block WHERE block_key = ?",
                Integer.class, blockKey);
        assertThat(count).isEqualTo(1);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = 'WORK_BLOCK' AND request_key = ?",
                Integer.class, requestKey);
        assertThat(idemCount).isEqualTo(1);
    }

    @Test
    void concurrentBlockRegisterAndBatchRescheduleAreMutuallyExclusive() throws Exception {
        String section = key("SEC");
        String blockKey = key("BLK");
        String oldKey = key("SCH-OLD");
        String newKey = key("SCH-NEW");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        // 批量改签把计划移入 10:00-11:00 窗口
        createDraft(newKey, section, 10, 11);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.registerWorkBlock(new RegisterWorkBlockRequest(
                                key("REQ"), blockKey, section, at(10), at(11), List.of()));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.batchReschedule(new BatchRescheduleRequest(
                                key("REQ"),
                                List.of(new BatchRescheduleItem(oldKey, newKey, 1, 1))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        if (outcomes.get(0) == Outcome.OK) {
            // 封锁生效：批量改签 422 回滚，旧仍发布、新草稿
            assertThat(service.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
            assertThat(service.getPlan(newKey).status()).isEqualTo("DRAFT");
            assertThat(service.getWorkBlock(blockKey).status()).isEqualTo("EFFECTIVE");
        } else {
            // 改签先提交：后继占据窗口，封锁登记 422，封锁不存在
            assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(service.getPlan(newKey).status()).isEqualTo("PUBLISHED");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rail_work_block WHERE block_key = ?",
                    Integer.class, blockKey)).isEqualTo(0);
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

    private void createDraft(String scheduleKey, String section,
                             int startHour, int startMinute, int endHour, int endMinute) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour, startMinute), at(endHour, endMinute)))));
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

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
