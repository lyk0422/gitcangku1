package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.WorkBlockService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BlockClearanceRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RegisterWorkBlockRequest;
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
 * 封锁并发裁决测试（真实 H2 内存库）：封锁登记与计划发布竞争同一交叉窗口时只能一方占用；
 * 同 requestKey 并发登记返回首次结果；并发批量改签竞争同一时隙恰好一项成功。
 */
@SpringBootTest
class WorkBlockConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    @Autowired
    private PlanService planService;

    @Autowired
    private WorkBlockService blockService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentBlockRegisterAndPublishOnlyOneOccupiesWindow() throws Exception {
        String section = key("SEC");
        String planKey = key("SCH");
        createDraft(planKey, section, 10, 30, 10, 45);
        String blockKey = key("BLK");

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    // 登记线程：按“当前无相交已发布计划”提交空处理指令
                    try {
                        blockService.registerBlock(new RegisterWorkBlockRequest(
                                key("REQ"), blockKey, DAY, section, at(10), at(11), List.of()));
                        return Outcome.BLOCK_OK;
                    } catch (ApiException e) {
                        // 发布先提交时，登记必须因存在未处理相交计划而 422
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("BLOCK_CLEARANCE_REQUIRED");
                        return Outcome.BLOCK_REJECTED;
                    }
                },
                () -> {
                    try {
                        planService.publish(planKey, key("REQ"));
                        return Outcome.PUBLISH_OK;
                    } catch (ApiException e) {
                        // 封锁先生效时，发布必须 422 且计划保持草稿
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("SLOT_CONFLICT");
                        return Outcome.PUBLISH_REJECTED;
                    }
                }));

        // 恰好一方占用交叉窗口，互不可共存，两种提交顺序都合法
        long blockOk = outcomes.stream().filter(o -> o == Outcome.BLOCK_OK).count();
        long publishOk = outcomes.stream().filter(o -> o == Outcome.PUBLISH_OK).count();
        assertThat(blockOk + publishOk).isEqualTo(1);
        Integer blockCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_block WHERE block_key = ?",
                Integer.class, blockKey);
        String planStatus = planService.getPlan(planKey).status();
        if (blockOk == 1) {
            assertThat(blockCount).isEqualTo(1);
            assertThat(planStatus).isEqualTo("DRAFT");
        } else {
            assertThat(blockCount).isZero();
            assertThat(planStatus).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void concurrentRegisterSameRequestKeyReturnsFirstResult() throws Exception {
        String section = key("SEC");
        String blockKey = key("BLK");
        String requestKey = key("REQ");
        int threads = 4;

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                blockService.registerBlock(new RegisterWorkBlockRequest(
                        requestKey, blockKey, DAY, section, at(10), at(11), List.of()));
                return "OK";
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        for (Future<String> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        }
        pool.shutdown();

        Integer blockCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_block WHERE block_key = ?",
                Integer.class, blockKey);
        assertThat(blockCount).isEqualTo(1);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = 'WORKBLOCK' AND request_key = ?",
                Integer.class, requestKey);
        assertThat(idemCount).isEqualTo(1);
    }

    @Test
    void concurrentBatchReschedulesRacingSameSlotOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        // 先登记一个不影响 11:00 时隙的封锁（10-11），批量改签必须避让它
        blockService.registerBlock(new RegisterWorkBlockRequest(
                key("REQ"), key("BLK"), DAY, section, at(10), at(11), List.of()));

        String oldA = key("SCH");
        String oldB = key("SCH");
        createDraft(oldA, section, 7, 0, 7, 30);
        createDraft(oldB, section, 7, 30, 8, 0);
        planService.publish(oldA, key("REQ"));
        planService.publish(oldB, key("REQ"));
        String newA = key("SCH");
        String newB = key("SCH");
        createDraft(newA, section, 11, 0, 11, 30);
        createDraft(newB, section, 11, 0, 11, 30);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        blockService.batchReschedule(new BatchRescheduleRequest(key("REQ"),
                                List.of(new BlockClearanceRequest(oldA, newA, 1, 1))));
                        return Outcome.BATCH_OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.BATCH_REJECTED;
                    }
                },
                () -> {
                    try {
                        blockService.batchReschedule(new BatchRescheduleRequest(key("REQ"),
                                List.of(new BlockClearanceRequest(oldB, newB, 1, 1))));
                        return Outcome.BATCH_OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.BATCH_REJECTED;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.BATCH_OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.BATCH_REJECTED).count()).isEqualTo(1);
        List<String> newStatuses = List.of(
                planService.getPlan(newA).status(), planService.getPlan(newB).status());
        assertThat(newStatuses).containsExactlyInAnyOrder("PUBLISHED", "DRAFT");
        // 11:00-11:30 最终只有一张计划生效
        long publishedAtSlot = planService.getPublishedSlots(DAY, section).stream()
                .filter(s -> s.startUtc().equals(at(11))).count();
        assertThat(publishedAtSlot).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        BLOCK_OK,
        BLOCK_REJECTED,
        PUBLISH_OK,
        PUBLISH_REJECTED,
        BATCH_OK,
        BATCH_REJECTED
    }

    private void createDraft(String scheduleKey, String section,
                             int startHour, int startMinute, int endHour, int endMinute) {
        List<OccupancyRequest> occupancies = List.of(new OccupancyRequest(
                "G-" + UUID.randomUUID(), section,
                at(startHour, startMinute), at(endHour, endMinute)));
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

    private static Instant at(int hour) {
        return at(hour, 0);
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
