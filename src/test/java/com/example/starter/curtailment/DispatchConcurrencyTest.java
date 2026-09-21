package com.example.starter.curtailment;

import com.example.starter.curtailment.commitment.CommitmentService;
import com.example.starter.curtailment.commitment.CreateCommitmentRequest;
import com.example.starter.curtailment.dispatch.AllocationRequest;
import com.example.starter.curtailment.dispatch.CommandRequest;
import com.example.starter.curtailment.dispatch.CreateDispatchRequest;
import com.example.starter.curtailment.dispatch.DispatchService;
import com.example.starter.curtailment.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界测试：两个冲突草稿并发发布时最多一份成功。
 */
@SpringBootTest
class DispatchConcurrencyTest {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private CommitmentService commitmentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM dispatch_event");
        jdbc.update("DELETE FROM dispatch_allocation");
        jdbc.update("DELETE FROM curtailment_dispatch");
        jdbc.update("DELETE FROM capacity_commitment");
        jdbc.update("DELETE FROM idempotent_command");
    }

    @Test
    void concurrentPublishAtMostOneSucceeds() throws Exception {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-02T00:00:00Z");
        Instant execFrom = Instant.parse("2026-10-01T10:00:00Z");
        Instant execTo = Instant.parse("2026-10-01T12:00:00Z");

        commitmentService.create(new CreateCommitmentRequest("cmd-c1", "cm-1", "site-1", from, to, "100"));
        dispatchService.create(new CreateDispatchRequest("cmd-d1", "dp-1", "feeder-1", execFrom, execTo, "60",
                List.of(new AllocationRequest("site-1", "60"))));
        dispatchService.create(new CreateDispatchRequest("cmd-d2", "dp-2", "feeder-1", execFrom, execTo, "60",
                List.of(new AllocationRequest("site-1", "60"))));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> first = pool.submit(() -> publishWhenReady("dp-1", "cmd-p1", ready, start));
            Future<Outcome> second = pool.submit(() -> publishWhenReady("dp-2", "cmd-p2", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Outcome one = first.get(30, TimeUnit.SECONDS);
            Outcome two = second.get(30, TimeUnit.SECONDS);

            int successes = (one.success ? 1 : 0) + (two.success ? 1 : 0);
            assertThat(successes).as("并发发布冲突草稿最多一份成功").isEqualTo(1);
            Outcome failure = one.success ? two : one;
            assertThat(failure.status).as("失败方应为容量不足 422")
                    .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            Integer published = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM curtailment_dispatch WHERE status = 'PUBLISHED'", Integer.class);
            assertThat(published).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Outcome publishWhenReady(String dispatchKey, String commandKey, CountDownLatch ready,
                                     CountDownLatch start) {
        try {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            dispatchService.publish(dispatchKey, new CommandRequest(commandKey));
            return new Outcome(true, null);
        } catch (ApiException ex) {
            return new Outcome(false, ex.status());
        } catch (Exception ex) {
            throw new IllegalStateException("并发发布出现非业务异常", ex);
        }
    }

    private record Outcome(boolean success, HttpStatus status) {
    }
}
