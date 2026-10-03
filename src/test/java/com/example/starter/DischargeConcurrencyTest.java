package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.exception.BusinessException;
import com.example.starter.service.DischargeService;
import com.example.starter.service.view.BatchDetailView;
import com.example.starter.service.view.CellView;
import com.example.starter.service.view.HandoverView;
import com.example.starter.service.view.ReadingView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发边界测试（真实 H2）：并发入批争夺同一电芯、读数作废与交接竞争。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class DischargeConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(3600);

    @Autowired
    private DischargeService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        clock.setInstant(T0);
        jdbc.update("DELETE FROM handover_snapshot");
        jdbc.update("DELETE FROM cell_isolation");
        jdbc.update("DELETE FROM cell_reading");
        jdbc.update("DELETE FROM battery_cell");
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM discharge_batch");
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private String newBatch() {
        String batchNo = "BATCH-" + UUID.randomUUID().toString().substring(0, 8);
        service.createBatch(batchNo);
        return batchNo;
    }

    private <T> Future<T> startTogether(CountDownLatch ready, CountDownLatch go, Callable<T> task) {
        return executor.submit(() -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return task.call();
        });
    }

    @Test
    void concurrentEnroll_sameCell_onlyOneSucceeds_andLoserKeyReusable() throws Exception {
        String batch1 = newBatch();
        String batch2 = newBatch();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<CellView> f1 = startTogether(ready, go,
                () -> service.enrollCell(batch1, "REQ-RACE-1", "CELL-RACE", new BigDecimal("3.70")));
        Future<CellView> f2 = startTogether(ready, go,
                () -> service.enrollCell(batch2, "REQ-RACE-2", "CELL-RACE", new BigDecimal("3.70")));
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();

        List<Future<CellView>> futures = List.of(f1, f2);
        int successes = 0;
        int rejections = 0;
        for (Future<CellView> f : futures) {
            try {
                f.get(15, TimeUnit.SECONDS);
                successes++;
            } catch (Exception e) {
                assertThat(e.getCause()).isInstanceOf(BusinessException.class);
                rejections++;
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(rejections).isEqualTo(1);

        Integer cellCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM battery_cell WHERE cell_code = 'CELL-RACE'", Integer.class);
        assertThat(cellCount).isEqualTo(1);

        // 失败方请求键未被占用：换一枚电芯用同一 requestId 重新入批成功
        CellView retry = service.enrollCell(batch2, "REQ-RACE-2", "CELL-RACE-2", new BigDecimal("3.70"));
        assertThat(retry.cellCode()).isEqualTo("CELL-RACE-2");
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'REQ-RACE-2'", Integer.class);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentVoidAndHandover_consistentFinalState() throws Exception {
        String batchNo = newBatch();
        service.enrollCell(batchNo, "REQ-E1", "CELL-001", new BigDecimal("3.70"));
        ReadingView reading = service.recordReading(batchNo, "REQ-R1", "CELL-001",
                new BigDecimal("0.40"), T1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<ReadingView> voidFuture = startTogether(ready, go,
                () -> service.voidReading(reading.id()));
        Future<HandoverView> handoverFuture = startTogether(ready, go,
                () -> service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B"));
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();

        boolean voidSucceeded = succeeded(voidFuture);
        boolean handoverSucceeded = succeeded(handoverFuture);
        assertThat(voidSucceeded).isNotEqualTo(handoverSucceeded);

        BatchDetailView detail = service.getBatch(batchNo);
        if (handoverSucceeded) {
            assertThat(detail.status()).isEqualTo("HANDED_OVER");
            assertThat(detail.handoverSnapshot()).hasSize(1);
            assertThat(detail.handoverSnapshot().get(0).readingId()).isEqualTo(reading.id());
            assertThat(detail.cells().get(0).readings().get(0).status()).isEqualTo("VALID");
        } else {
            assertThat(detail.status()).isEqualTo("OPEN");
            assertThat(detail.handoverSnapshot()).isEmpty();
            assertThat(detail.cells().get(0).readings().get(0).status()).isEqualTo("VOID");
        }
    }

    private boolean succeeded(Future<?> future) throws Exception {
        try {
            future.get(15, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            assertThat(e.getCause()).isInstanceOf(BusinessException.class);
            return false;
        }
    }
}