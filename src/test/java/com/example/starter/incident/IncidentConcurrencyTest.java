package com.example.starter.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * 并发边界测试：交接接受与状态变更并发时按事务提交顺序生效；
 * 同一 commandKey 并发重放只生效一次。
 */
@SpringBootTest
class IncidentConcurrencyTest {

    @Autowired
    private IncidentService service;

    private static String key() {
        return "INC-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String containedIncidentWithPendingHandover(String actor, String target) {
        String key = key();
        service.report(new ReportIncidentRequest(key, Severity.S1, "核心库主从延迟", "ops-bot"));
        service.takeCommand(key, actor, new TakeCommandRequest("cmd-take-" + key));
        service.initiateHandover(key, actor, new InitiateHandoverRequest("cmd-ho-" + key, target));
        service.changeStatus(key, actor, new ChangeStatusRequest("cmd-ct-" + key, IncidentStatus.CONTAINED));
        return key;
    }

    private static <T> List<Future<T>> runConcurrently(Callable<T> first, Callable<T> second)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<T> wrapFirst = () -> {
                ready.countDown();
                start.await();
                return first.call();
            };
            Callable<T> wrapSecond = () -> {
                ready.countDown();
                start.await();
                return second.call();
            };
            Future<T> f1 = pool.submit(wrapFirst);
            Future<T> f2 = pool.submit(wrapSecond);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(f1, f2);
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private static int successes(List<Future<Object>> results) throws Exception {
        int ok = 0;
        for (Future<Object> future : results) {
            Object value = future.get(30, TimeUnit.SECONDS);
            if (!(value instanceof ApiException)) {
                ok++;
            }
        }
        return ok;
    }

    private static Callable<Object> guarding(RunnableThrowingCall call) {
        return () -> {
            try {
                call.run();
                return new Object();
            } catch (ApiException ex) {
                return ex;
            }
        };
    }

    @FunctionalInterface
    private interface RunnableThrowingCall {
        void run();
    }

    @Test
    void acceptHandoverAndResolveRaceSettlesInCommitOrder() throws Exception {
        String key = containedIncidentWithPendingHandover("alice", "bob");

        List<Future<Object>> results = runConcurrently(
                guarding(() -> service.acceptHandover(key, "bob", new AcceptHandoverRequest("cmd-acc-" + key))),
                guarding(() -> service.changeStatus(key, "alice",
                        new ChangeStatusRequest("cmd-rs-" + key, IncidentStatus.RESOLVED))));

        assertEquals(1, successes(results), "并发接受交接与解决必须恰好一个成功");

        IncidentView view = service.getIncident(key);
        if (view.status() == IncidentStatus.RESOLVED) {
            // 解决先提交：指挥人不变，交接作废
            assertEquals("alice", view.commanderId());
        } else {
            // 接受先提交：指挥人切换，事件仍为 CONTAINED
            assertEquals(IncidentStatus.CONTAINED, view.status());
            assertEquals("bob", view.commanderId());
        }
    }

    @Test
    void concurrentSameCommandKeyReplayAppliesOnce() throws Exception {
        String key = key();
        service.report(new ReportIncidentRequest(key, Severity.S3, "磁盘水位告警", "ops-bot"));

        List<Future<Object>> results = runConcurrently(
                guarding(() -> service.takeCommand(key, "alice", new TakeCommandRequest("cmd-take-" + key))),
                guarding(() -> service.takeCommand(key, "alice", new TakeCommandRequest("cmd-take-" + key))));

        assertEquals(2, successes(results), "同键同参并发重放应都返回首次结果");

        IncidentHistoryView history = service.getHistory(key);
        long tookCommand = history.events().stream()
                .filter(event -> "TOOK_COMMAND".equals(event.eventType()))
                .count();
        assertEquals(1, tookCommand, "接管只能生效一次");
        assertEquals("alice", history.incident().commanderId());
    }

    @Test
    void concurrentTakeCommandByDifferentActorsHasSingleWinner() throws Exception {
        String key = key();
        service.report(new ReportIncidentRequest(key, Severity.S2, "缓存命中率骤降", "ops-bot"));

        List<Future<Object>> results = runConcurrently(
                guarding(() -> service.takeCommand(key, "alice", new TakeCommandRequest("cmd-a-" + key))),
                guarding(() -> service.takeCommand(key, "bob", new TakeCommandRequest("cmd-b-" + key))));

        assertEquals(1, successes(results), "并发接管只能一人成功");
        IncidentView view = service.getIncident(key);
        assertTrue("alice".equals(view.commanderId()) || "bob".equals(view.commanderId()));
        assertEquals(IncidentStatus.COMMANDING, view.status());
    }
}
