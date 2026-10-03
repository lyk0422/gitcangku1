package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.domain.AssignmentStatus;
import com.example.starter.service.ApiException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 并发规则测试：真实线程竞争，闩门协调同时进入，核对最终业务状态与数据库事实。
 */
class PilotageConcurrencyTest extends AbstractPilotageTest {

    private record Outcome(Object result, ApiException error) {
        static Outcome of(Object result) {
            return new Outcome(result, null);
        }

        static Outcome failed(ApiException error) {
            return new Outcome(null, error);
        }
    }

    @Test
    void concurrentOverlappingAssignAtMostOneSucceeds() throws Exception {
        long pilot = pilotId("P001");
        long seg1 = newSegment("MV-CA", 1, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        long seg2 = newSegment("MV-CB", 1, "2026-10-04T09:30:00Z", "2026-10-04T10:30:00Z", "锚地B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Outcome> f1 = pool.submit(() -> runAssign(ready, start, "r-cc-a", seg1, pilot));
            Future<Outcome> f2 = pool.submit(() -> runAssign(ready, start, "r-cc-b", seg2, pilot));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));

            long successes = outcomes.stream().filter(o -> o.error() == null).count();
            assertEquals(1, successes, "同一引航员重叠航段并发指派最多成功一个");
            Outcome failure = outcomes.stream().filter(o -> o.error() != null).findFirst().orElseThrow();
            assertEquals(HttpStatus.CONFLICT, failure.error().status());
            assertEquals("PILOT_INTERVAL_OVERLAP", failure.error().code());

            long active = assignmentRepo.findByPilotId(pilot).stream()
                    .filter(a -> a.status() == AssignmentStatus.ACTIVE).count();
            assertEquals(1, active, "数据库中该引航员应只有一个有效占用");

            // 失败不占请求键：换不重叠航段后同一键可成功
            long seg3 = newSegment("MV-CC", 1, "2026-10-04T11:00:00Z", "2026-10-04T12:00:00Z", "锚地C");
            String failedKey = outcomes.get(0).error() != null ? "r-cc-a" : "r-cc-b";
            var retried = service.assign(failedKey, seg3, pilot);
            assertEquals(AssignmentStatus.ACTIVE, retried.assignment().status());
        } finally {
            pool.shutdownNow();
        }
    }

    private Outcome runAssign(CountDownLatch ready, CountDownLatch start, String requestId, long segmentId,
                              long pilotId) throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            return Outcome.of(service.assign(requestId, segmentId, pilotId));
        } catch (ApiException e) {
            return Outcome.failed(e);
        }
    }

    @Test
    void boardAndRevokeRaceLeavesExactlyOneState() throws Exception {
        long pilot = pilotId("P001");
        long segId = newSegment("MV-CR", 1, "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z", "锚地A");
        long assignmentId = service.assign("r-cr-1", segId, pilot).assignment().id();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Outcome> boardFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return Outcome.of(service.board(assignmentId, "b-cr-1"));
                } catch (ApiException e) {
                    return Outcome.failed(e);
                }
            });
            Future<Outcome> revokeFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    return Outcome.of(service.revoke(assignmentId));
                } catch (ApiException e) {
                    return Outcome.failed(e);
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Outcome> outcomes = List.of(boardFuture.get(15, TimeUnit.SECONDS),
                    revokeFuture.get(15, TimeUnit.SECONDS));

            long successes = outcomes.stream().filter(o -> o.error() == null).count();
            assertEquals(1, successes, "登轮与撤销竞争只能有一个生效");
            assertEquals(HttpStatus.CONFLICT,
                    outcomes.stream().filter(o -> o.error() != null).findFirst().orElseThrow().error().status());

            var persisted = assignmentRepo.findById(assignmentId).orElseThrow();
            assertTrue(persisted.status() == AssignmentStatus.BOARDED || persisted.status() == AssignmentStatus.REVOKED,
                    "最终状态必须是登轮或撤销之一，实际: " + persisted.status());
            if (persisted.status() == AssignmentStatus.BOARDED) {
                ApiException e = assertConflictOnRevoke(assignmentId);
                assertEquals("ASSIGNMENT_NOT_REVOCABLE", e.code());
            } else {
                ApiException e = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                        () -> service.board(assignmentId, "b-cr-2"));
                assertEquals(HttpStatus.CONFLICT, e.status());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private ApiException assertConflictOnRevoke(long assignmentId) {
        return org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.revoke(assignmentId));
    }
}