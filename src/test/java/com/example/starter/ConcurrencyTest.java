package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.error.ApiException;
import com.example.starter.model.AssignmentRow;
import com.example.starter.model.SegmentRow;
import com.example.starter.service.AssignmentService;
import com.example.starter.service.SegmentService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发测试：同一引航员重叠航段的并发指派最多成功一个；登轮与撤销竞争只有一种有效状态。
 * 通过 CountDownLatch 协调真实并发，全部在真实 H2 上执行并设超时。
 */
@SpringBootTest
class ConcurrencyTest {

    @Autowired
    private SegmentService segmentService;
    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM boarding_point_change");
        jdbc.update("DELETE FROM assignment");
        jdbc.update("DELETE FROM segment");
        clock.setInstant(Instant.parse("2026-10-04T08:00:00Z"));
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private long newSegment(String vessel, String start, String end) {
        SegmentRow row = segmentService.create(vessel, 1, Instant.parse(start),
                Instant.parse(end), "锚地A");
        return row.id();
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> wrapped = tasks.stream().<Callable<T>>map(task -> () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return task.call();
        }).toList();
        List<Future<T>> futures = wrapped.stream().map(executor::submit).toList();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(15, TimeUnit.SECONDS));
        }
        return results;
    }

    private static Object attempt(Callable<?> action) {
        try {
            return action.call();
        } catch (ApiException e) {
            return e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Timeout(60)
    void concurrentAssignSamePilotOverlap_atMostOneSucceedsAndFailureKeepsNoRequestKey()
            throws Exception {
        long seg1 = newSegment("V100", "2026-10-04T09:00:00Z", "2026-10-04T10:00:00Z");
        long seg2 = newSegment("V101", "2026-10-04T09:30:00Z", "2026-10-04T10:30:00Z");

        List<Object> results = runConcurrently(List.of(
                () -> attempt(() -> assignmentService.assign(seg1, "P001", "req-cc1")),
                () -> attempt(() -> assignmentService.assign(seg2, "P001", "req-cc2"))));

        long successes = results.stream().filter(r -> r instanceof AssignmentRow).count();
        long conflicts = results.stream().filter(r -> r instanceof ApiException e
                && e.status() == HttpStatus.CONFLICT).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assignment WHERE status = 'ACTIVE'", Integer.class);
        assertThat(active).isEqualTo(1);

        // 失败不占请求键：失败的 requestId 可再次用于不重叠航段
        String failedRequestId = results.get(0) instanceof ApiException ? "req-cc1" : "req-cc2";
        long seg3 = newSegment("V102", "2026-10-04T10:30:00Z", "2026-10-04T11:30:00Z");
        AssignmentRow retried = assignmentService.assign(seg3, "P001", failedRequestId);
        assertThat(retried.requestId()).isEqualTo(failedRequestId);
    }

    @Test
    @Timeout(120)
    void boardAndCancelRace_exactlyOneEffectiveState() throws Exception {
        for (int i = 0; i < 20; i++) {
            Instant startInstant = Instant.parse("2026-10-04T09:00:00Z").plusSeconds(i * 7200L);
            String start = startInstant.toString();
            String end = startInstant.plusSeconds(3600).toString();
            long seg = newSegment("V2" + i, start, end);
            assignmentService.assign(seg, "P002", "req-r" + i);
            final String boardRequestId = "req-rb" + i;
            clock.setInstant(Instant.parse(start).plusSeconds(300));

            List<Object> results = runConcurrently(List.of(
                    () -> attempt(() -> assignmentService.board(seg, boardRequestId)),
                    () -> attempt(() -> {
                        assignmentService.cancelAssignment(seg);
                        return "cancelled";
                    })));

            long successes = results.stream()
                    .filter(r -> !(r instanceof ApiException)).count();
            long conflicts = results.stream().filter(r -> r instanceof ApiException e
                    && e.status() == HttpStatus.CONFLICT).count();
            assertThat(successes).as("第 %s 轮只能有一种有效状态", i).isEqualTo(1);
            assertThat(conflicts).as("第 %s 轮失败方必须是 409", i).isEqualTo(1);

            String assignmentStatus = jdbc.queryForObject(
                    "SELECT status FROM assignment WHERE segment_id = ?", String.class, seg);
            boolean boardedWon = results.get(0) instanceof AssignmentRow;
            if (boardedWon) {
                assertThat(assignmentStatus).isEqualTo("ACTIVE");
                Instant boardedAt = jdbc.queryForObject(
                        "SELECT boarded_at_utc FROM assignment WHERE segment_id = ?",
                        java.time.LocalDateTime.class, seg).toInstant(java.time.ZoneOffset.UTC);
                assertThat(boardedAt).isEqualTo(Instant.parse(start).plusSeconds(300));
                // 确认离轮释放占用，避免影响下一轮
                assignmentService.disembark(seg, Instant.parse(start).plusSeconds(3000));
            } else {
                assertThat(assignmentStatus).isEqualTo("RELEASED");
            }
        }
    }
}