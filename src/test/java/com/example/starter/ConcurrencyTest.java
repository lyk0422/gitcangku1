package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 并发状态测试：真实 H2 行锁串行化，并发摘除/确认/附着按提交顺序判定，
 * 同一标签任何时刻只由一个站点持有。
 */
class ConcurrencyTest extends ApiTestSupport {

    private static final int TIMEOUT_SECONDS = 15;

    /** 两个线程同一时刻附着同一标签：恰好一个成功，库里只有一条有效区间。 */
    @Test
    void concurrentAttachSameTagYieldsSingleWinner() throws Exception {
        registerTag("C-01", "ST-ALPHA");
        CountDownLatch gate = new CountDownLatch(1);
        Callable<ResponseEntity<String>> attachA = () -> {
            gate.await();
            return attach("req-ca", "C-01", "AN-9001", "ST-ALPHA", "2026-02-01T08:00:00Z");
        };
        Callable<ResponseEntity<String>> attachB = () -> {
            gate.await();
            return attach("req-cb", "C-01", "AN-9002", "ST-ALPHA", "2026-02-01T08:00:00Z");
        };

        List<ResponseEntity<String>> results = runConcurrently(gate, attachA, attachB);
        long created = results.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        long conflicted = results.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
        assertThat(created).isEqualTo(1);
        assertThat(conflicted).isEqualTo(1);

        assertThat(countRows("attachment")).isEqualTo(1);
        assertThat(json(rest.getForEntity("/api/tags/C-01", String.class)).get("status").asText())
                .isEqualTo("ATTACHED");
    }

    /** 并发确认与取消：恰好一个生效，最终持有站点与交接状态一致，不留半个转移。 */
    @Test
    void concurrentConfirmAndCancelResolveInCommitOrder() throws Exception {
        registerTag("C-02", "ST-ALPHA");
        attach("req-cc1", "C-02", "AN-9101", "ST-ALPHA", "2026-02-01T08:00:00Z");
        detach("C-02", "ST-ALPHA", "2026-02-01T10:00:00Z");
        var init = initTransfer("req-cc2", "C-02", "ST-ALPHA", "ST-BRAVO", "2026-02-01T10:00:00Z");
        long transferId = json(init).get("id").asLong();

        CountDownLatch gate = new CountDownLatch(1);
        Callable<ResponseEntity<String>> confirm = () -> {
            gate.await();
            return confirmTransfer(transferId, "ST-BRAVO", "2026-02-01T10:05:00Z");
        };
        Callable<ResponseEntity<String>> cancel = () -> {
            gate.await();
            return rest.postForEntity("/api/transfers/" + transferId + "/cancel", null, String.class);
        };

        List<ResponseEntity<String>> results = runConcurrently(gate, confirm, cancel);
        long ok = results.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        long conflicted = results.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicted).isEqualTo(1);

        var tag = json(rest.getForEntity("/api/tags/C-02", String.class));
        String transferStatus = jdbc.sql("SELECT status FROM tag_transfer WHERE id = " + transferId)
                .query(String.class).single();
        if ("CONFIRMED".equals(transferStatus)) {
            assertThat(tag.get("holderStationCode").asText()).isEqualTo("ST-BRAVO");
        } else {
            assertThat(transferStatus).isEqualTo("CANCELLED");
            assertThat(tag.get("holderStationCode").asText()).isEqualTo("ST-ALPHA");
        }
        // 同一标签同一时刻只能有一个持有站点
        assertThat(countRows("tag")).isEqualTo(1);
    }

    /** 并发摘除同一标签：恰好一个成功，历史区间只被冻结一次。 */
    @Test
    void concurrentDetachFreezesIntervalOnce() throws Exception {
        registerTag("C-03", "ST-ALPHA");
        attach("req-cd1", "C-03", "AN-9201", "ST-ALPHA", "2026-02-01T08:00:00Z");

        CountDownLatch gate = new CountDownLatch(1);
        Callable<ResponseEntity<String>> detachA = () -> {
            gate.await();
            return detach("C-03", "ST-ALPHA", "2026-02-01T10:00:00Z");
        };
        Callable<ResponseEntity<String>> detachB = () -> {
            gate.await();
            return detach("C-03", "ST-ALPHA", "2026-02-01T10:00:00Z");
        };

        List<ResponseEntity<String>> results = runConcurrently(gate, detachA, detachB);
        assertThat(results.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count())
                .isEqualTo(1);
        assertThat(countRows("attachment")).isEqualTo(1);
        var endAt = jdbc.sql("SELECT end_at FROM attachment")
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).single();
        assertThat(endAt).isEqualTo(java.time.Instant.parse("2026-02-01T10:00:00Z"));
    }

    private List<ResponseEntity<String>> runConcurrently(CountDownLatch gate,
            Callable<ResponseEntity<String>> first, Callable<ResponseEntity<String>> second)
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> f1 = pool.submit(first);
            Future<ResponseEntity<String>> f2 = pool.submit(second);
            gate.countDown();
            return List.of(
                    f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }
}