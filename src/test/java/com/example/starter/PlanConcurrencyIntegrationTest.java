package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 并发集成测试：并发签发最多一个有效指令；人员进入与执行回报竞争时按已提交顺序裁决，
 * 不允许出现"既失效又成功"。所有并发操作设超时并核对数据库最终状态。
 */
class PlanConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Test
    void concurrentIssueCreatesAtMostOneActiveCommand() throws Exception {
        long planId = createThreePointPlan();
        confirmAll(planId);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "REQ-CONCURRENT-" + i;
            futures.add(pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return issue(planId, requestId, "班长王").getResponse().getStatus();
            }));
        }
        gate.countDown();
        int created = 0;
        int rejected = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(15, TimeUnit.SECONDS);
            if (status == 201) {
                created++;
            } else if (status == 422) {
                rejected++;
            }
        }
        pool.shutdownNow();
        assertEquals(1, created, "并发签发只能成功一次");
        assertEquals(threads - 1, rejected, "其余并发签发应被 422 拒绝");

        JsonNode detail = body(getJson("/api/plans/" + planId));
        assertEquals(1, detail.get("commands").size());
        assertEquals("ACTIVE", detail.get("commands").get(0).get("status").asText());
        Integer commandRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blast_command WHERE plan_id = " + planId, Integer.class);
        assertEquals(1, commandRows.intValue());
    }

    @Test
    void entryAndExecutionRaceResolvesToSingleCommittedOutcome() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            long planId = createThreePointPlan();
            confirmAll(planId);
            final String execRequestId = "REQ-RACE-EXEC-" + iteration;
            long commandId = body(issue(planId, "REQ-RACE-ISSUE-" + iteration, "班长王"))
                    .get("commandId").asLong();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch gate = new CountDownLatch(1);
            Future<MvcResult> entryFuture = pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return entry(planId, "巡查员");
            });
            Future<MvcResult> execFuture = pool.submit(() -> {
                gate.await(5, TimeUnit.SECONDS);
                return execute(commandId, execRequestId, "班长王");
            });
            gate.countDown();
            MvcResult entryResult = entryFuture.get(15, TimeUnit.SECONDS);
            MvcResult execResult = execFuture.get(15, TimeUnit.SECONDS);
            pool.shutdownNow();

            int entryStatus = entryResult.getResponse().getStatus();
            int execStatus = execResult.getResponse().getStatus();
            boolean entryCommitted = entryStatus == 201;
            boolean execCommitted = execStatus == 201;
            assertTrue(entryCommitted != execCommitted,
                    "进入事件与执行回报必须恰有一个成功: entry=" + entryStatus
                            + " exec=" + execStatus);

            JsonNode detail = body(getJson("/api/plans/" + planId));
            String commandStatus = detail.get("commands").get(0).get("status").asText();
            int executions = detail.get("executions").size();
            if (execCommitted) {
                assertEquals("COMPLETED", detail.get("status").asText());
                assertEquals("EXECUTED", commandStatus);
                assertEquals(1, executions);
                assertEquals(422, entryStatus);
            } else {
                assertEquals("COMMAND_INVALIDATED", body(execResult).get("code").asText());
                assertEquals("INVALIDATED", commandStatus);
                assertEquals(0, executions);
            }
        }
    }
}