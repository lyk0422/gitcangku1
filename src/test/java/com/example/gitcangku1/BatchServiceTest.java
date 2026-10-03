package com.example.gitcangku1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.gitcangku1.domain.BatchStatus;
import com.example.gitcangku1.domain.MeasureType;
import com.example.gitcangku1.repo.BatchRepository;
import com.example.gitcangku1.service.ApiException;
import com.example.gitcangku1.service.BatchService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class BatchServiceTest {

    @Autowired
    BatchService batchService;
    @Autowired
    BatchRepository batches;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_errata");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM recheck_adjudication");
        jdbc.update("DELETE FROM color_measurement");
        jdbc.update("DELETE FROM dye_batch");
    }

    private String no() {
        return "B-" + UUID.randomUUID();
    }

    @Test
    void createBatchValidatesRollCount() {
        var e1 = assertThrows(ApiException.class,
                () -> batchService.createBatch(no(), 50, 0, 0, 0));
        assertEquals(HttpStatus.BAD_REQUEST, e1.status());
        var e2 = assertThrows(ApiException.class,
                () -> batchService.createBatch(no(), 50, 0, 0, 31));
        assertEquals(HttpStatus.BAD_REQUEST, e2.status());
        var ok = batchService.createBatch(no(), 50, 0, 0, 30);
        assertEquals(BatchStatus.OPEN, ok.status());
        assertEquals(1, ok.targetVersion());
    }

    @Test
    void duplicateBatchNoRejected() {
        String batchNo = no();
        batchService.createBatch(batchNo, 50, 0, 0, 2);
        var e = assertThrows(ApiException.class,
                () -> batchService.createBatch(batchNo, 50, 0, 0, 2));
        assertEquals(HttpStatus.CONFLICT, e.status());
    }

    @Test
    void measurementIdempotentReplayAndConflict() {
        var batch = batchService.createBatch(no(), 50, 0, 0, 2);
        var at = Instant.parse("2026-10-01T08:00:00Z");
        var first = batchService.recordMeasurement(batch.id(), "req-1", 1,
                MeasureType.ORIGINAL, 51, 1, 1, at);
        assertEquals(1, first.targetVersion());

        var replay = batchService.recordMeasurement(batch.id(), "req-1", 1,
                MeasureType.ORIGINAL, 51, 1, 1, at);
        assertEquals(first.id(), replay.id());

        var conflict = assertThrows(ApiException.class,
                () -> batchService.recordMeasurement(batch.id(), "req-1", 1,
                        MeasureType.ORIGINAL, 52, 1, 1, at));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
    }

    @Test
    void remeasureKeepsHistoryAndLatestWins() {
        var batch = batchService.createBatch(no(), 50, 0, 0, 1);
        var at = Instant.parse("2026-10-01T08:00:00Z");
        batchService.recordMeasurement(batch.id(), "req-a", 1, MeasureType.ORIGINAL, 53, 0, 0, at);
        var latest = batchService.recordMeasurement(batch.id(), "req-b", 1,
                MeasureType.ORIGINAL, 51, 1, 1, at.plusSeconds(60));
        var all = jdbc.queryForObject(
                "SELECT COUNT(*) FROM color_measurement WHERE batch_id=" + batch.id(), Long.class);
        assertEquals(2L, all);
        assertEquals(51.0, latest.l());
    }

    @Test
    void rollNoOutOfRangeRejected() {
        var batch = batchService.createBatch(no(), 50, 0, 0, 2);
        var e = assertThrows(ApiException.class,
                () -> batchService.recordMeasurement(batch.id(), "req-x", 3,
                        MeasureType.ORIGINAL, 51, 1, 1, Instant.parse("2026-10-01T08:00:00Z")));
        assertEquals(HttpStatus.BAD_REQUEST, e.status());
    }

    @Test
    void adjudicationRules() {
        var batch = batchService.createBatch(no(), 50, 0, 0, 1);
        var at = Instant.parse("2026-10-01T08:00:00Z");
        var noRecheck = assertThrows(ApiException.class,
                () -> batchService.adjudicate(batch.id(), 1, MeasureType.RECHECK, "qc-wang"));
        assertEquals(HttpStatus.BAD_REQUEST, noRecheck.status());

        batchService.recordMeasurement(batch.id(), "req-o", 1, MeasureType.ORIGINAL, 53, 0, 0, at);
        batchService.recordMeasurement(batch.id(), "req-r", 1, MeasureType.RECHECK, 51, 1, 1, at);
        var adj = batchService.adjudicate(batch.id(), 1, MeasureType.RECHECK, "qc-wang");
        assertEquals(MeasureType.RECHECK, adj.decision());

        var dup = assertThrows(ApiException.class,
                () -> batchService.adjudicate(batch.id(), 1, MeasureType.ORIGINAL, "qc-li"));
        assertEquals(HttpStatus.CONFLICT, dup.status());
    }

    @Test
    void updateTargetIncrementsVersion() {
        var batch = batchService.createBatch(no(), 50, 0, 0, 1);
        var updated = batchService.updateTarget(batch.id(), 51, 0, 0);
        assertEquals(2, updated.targetVersion());
        assertEquals(51.0, updated.targetL());
    }

    @Test
    void missingBatchIs404() {
        var e = assertThrows(ApiException.class, () -> batchService.updateTarget(999999, 1, 2, 3));
        assertEquals(HttpStatus.NOT_FOUND, e.status());
    }
}