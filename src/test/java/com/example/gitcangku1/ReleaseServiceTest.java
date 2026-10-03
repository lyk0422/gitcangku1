package com.example.gitcangku1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.gitcangku1.domain.BatchStatus;
import com.example.gitcangku1.domain.MeasureType;
import com.example.gitcangku1.repo.BatchRepository;
import com.example.gitcangku1.repo.ReleaseRepository;
import com.example.gitcangku1.service.ApiException;
import com.example.gitcangku1.service.BatchService;
import com.example.gitcangku1.service.ReleaseRejectedException;
import com.example.gitcangku1.service.ReleaseService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ReleaseServiceTest {

    static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    static final double SQRT3 = 1.7320508075688772;

    @Autowired
    BatchService batchService;
    @Autowired
    ReleaseService releaseService;
    @Autowired
    BatchRepository batches;
    @Autowired
    ReleaseRepository releases;
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

    private long newBatch(int rolls) {
        return batchService.createBatch("B-" + UUID.randomUUID(), 50, 0, 0, rolls).id();
    }

    private void measure(long batchId, int roll, double l, double a, double b) {
        batchService.recordMeasurement(batchId, "m-" + UUID.randomUUID(), roll,
                MeasureType.ORIGINAL, l, a, b, T0);
    }

    @Test
    void releaseFreezesAdoptedRecordsAndDelta() {
        long id = newBatch(2);
        measure(id, 1, 51, 1, 1);
        measure(id, 2, 50, 0, 0);
        var view = releaseService.release(id, "rel-1", null);
        assertEquals(2, view.items().size());
        assertEquals(SQRT3, view.items().get(0).deltaE(), 1e-12);
        assertEquals(0.0, view.items().get(1).deltaE(), 1e-12);
        assertEquals("ORIGINAL", view.items().get(0).adoptedSource());
        assertEquals(1, view.targetVersion());
        assertEquals(BatchStatus.RELEASED, batches.findById(id).orElseThrow().status());
        assertEquals(1, releases.countOrders(id));
    }

    @Test
    void missingRollRejectedAndFailedTxLeavesNoKey() {
        long id = newBatch(2);
        measure(id, 1, 51, 1, 1);
        var e = assertThrows(ReleaseRejectedException.class,
                () -> releaseService.release(id, "rel-reuse", null));
        assertEquals(List.of(2), e.rejectedRolls().get("MISSING"));
        assertEquals(0, releases.countOrders(id));
        assertTrue(releases.findOrderByRequestId("rel-reuse").isEmpty());

        measure(id, 2, 50, 0, 0);
        var view = releaseService.release(id, "rel-reuse", null);
        assertEquals(2, view.items().size());
    }

    @Test
    void undecidedRecheckRejected() {
        long id = newBatch(1);
        measure(id, 1, 53, 0, 0);
        batchService.recordMeasurement(id, "m-rc", 1, MeasureType.RECHECK, 51, 1, 1, T0);
        var e = assertThrows(ReleaseRejectedException.class,
                () -> releaseService.release(id, "rel-1", null));
        assertEquals(List.of(1), e.rejectedRolls().get("UNDECIDED_RECHECK"));
        assertEquals(0, releases.countOrders(id));
    }

    @Test
    void outOfToleranceRejectsWholeBatchNoPartialRelease() {
        long id = newBatch(2);
        measure(id, 1, 51, 1, 1);
        measure(id, 2, 53, 0, 0);
        var e = assertThrows(ReleaseRejectedException.class,
                () -> releaseService.release(id, "rel-1", null));
        assertEquals(List.of(2), e.rejectedRolls().get("OUT_OF_TOLERANCE"));
        assertEquals(0, releases.countOrders(id));
        assertEquals(BatchStatus.OPEN, batches.findById(id).orElseThrow().status());
    }

    @Test
    void boundaryDeltaExactlyTwoPasses() {
        long id = newBatch(1);
        measure(id, 1, 52, 0, 0);
        var view = releaseService.release(id, "rel-1", null);
        assertEquals(2.0, view.items().get(0).deltaE(), 1e-12);
    }

    @Test
    void staleTargetVersionRejectedThenRemeasurePasses() {
        long id = newBatch(1);
        measure(id, 1, 51, 1, 1);
        batchService.updateTarget(id, 49, 0, 0);
        var e = assertThrows(ReleaseRejectedException.class,
                () -> releaseService.release(id, "rel-1", null));
        assertEquals(List.of(1), e.rejectedRolls().get("STALE_TARGET_VERSION"));

        batchService.recordMeasurement(id, "m-v2", 1, MeasureType.ORIGINAL, 50, 1, 1, T0);
        var view = releaseService.release(id, "rel-1", null);
        assertEquals(2, view.targetVersion());
        assertEquals(SQRT3 - 0.0, view.items().get(0).deltaE(), 1e-9);
        Long kept = jdbc.queryForObject(
                "SELECT COUNT(*) FROM color_measurement WHERE batch_id=" + id, Long.class);
        assertEquals(2L, kept);
    }
    @Test
    void adjudicatedRecheckIsAdoptedAndFrozen() {
        long id = newBatch(1);
        measure(id, 1, 53, 0, 0);
        batchService.recordMeasurement(id, "m-rc", 1, MeasureType.RECHECK, 51, 1, 1, T0);
        batchService.adjudicate(id, 1, MeasureType.RECHECK, "qc-wang");
        var view = releaseService.release(id, "rel-1", null);
        var item = view.items().get(0);
        assertEquals("RECHECK", item.adoptedSource());
        assertEquals(SQRT3, item.deltaE(), 1e-12);
        assertEquals(51.0, item.l());
    }

    @Test
    void adjudicatedOriginalKeepsFailingDelta() {
        long id = newBatch(1);
        measure(id, 1, 53, 0, 0);
        batchService.recordMeasurement(id, "m-rc", 1, MeasureType.RECHECK, 51, 1, 1, T0);
        batchService.adjudicate(id, 1, MeasureType.ORIGINAL, "qc-wang");
        var e = assertThrows(ReleaseRejectedException.class,
                () -> releaseService.release(id, "rel-1", null));
        assertEquals(List.of(1), e.rejectedRolls().get("OUT_OF_TOLERANCE"));
    }

    @Test
    void releaseIdempotentReplayAndConflict() {
        long id = newBatch(1);
        measure(id, 1, 51, 1, 1);
        var first = releaseService.release(id, "rel-1", 1);
        var replay = releaseService.release(id, "rel-1", 1);
        assertEquals(first.releaseId(), replay.releaseId());
        assertEquals(1, releases.countOrders(id));

        var conflict = assertThrows(ApiException.class,
                () -> releaseService.release(id, "rel-1", 2));
        assertEquals(HttpStatus.CONFLICT, conflict.status());

        var second = releaseService.release(id, "rel-2", null);
        assertEquals(first.releaseId(), second.releaseId());
        assertEquals(1, releases.countOrders(id));
    }

    @Test
    void expectedTargetVersionMismatchIsConflict() {
        long id = newBatch(1);
        measure(id, 1, 51, 1, 1);
        var e = assertThrows(ApiException.class, () -> releaseService.release(id, "rel-1", 9));
        assertEquals(HttpStatus.CONFLICT, e.status());
        assertEquals(0, releases.countOrders(id));
    }

    @Test
    void measurementAndTargetChangeForbiddenAfterRelease() {
        long id = newBatch(1);
        measure(id, 1, 51, 1, 1);
        releaseService.release(id, "rel-1", null);
        var e1 = assertThrows(ApiException.class,
                () -> batchService.recordMeasurement(id, "m-late", 1,
                        MeasureType.ORIGINAL, 50, 0, 0, T0));
        assertEquals(HttpStatus.CONFLICT, e1.status());
        var e2 = assertThrows(ApiException.class,
                () -> batchService.updateTarget(id, 1, 2, 3));
        assertEquals(HttpStatus.CONFLICT, e2.status());
    }

    @Test
    void errataAppendOnlyAndFrozenDataUnchanged() {
        long id = newBatch(1);
        measure(id, 1, 51, 1, 1);
        var notReleased = assertThrows(ApiException.class,
                () -> releaseService.appendErrata(id, "提前勘误"));
        assertEquals(HttpStatus.CONFLICT, notReleased.status());

        var before = releaseService.release(id, "rel-1", null);
        releaseService.appendErrata(id, "卷1标签印刷错误，测色数据不变");
        releaseService.appendErrata(id, "补充：客户已确认接收");
        var after = releaseService.view(id);

        assertEquals(2, after.release().errata().size());
        assertEquals("卷1标签印刷错误，测色数据不变", after.release().errata().get(0).note());
        assertEquals(before.items(), after.release().items());
        assertEquals(SQRT3, after.release().items().get(0).deltaE(), 1e-12);
    }

    @Test
    void viewShowsTargetVersionMeasurementsAdjudicationAndDelta() {
        long id = newBatch(2);
        measure(id, 1, 51, 1, 1);
        measure(id, 2, 53, 0, 0);
        batchService.recordMeasurement(id, "m-rc", 2, MeasureType.RECHECK, 50, 0, 0, T0);
        batchService.adjudicate(id, 2, MeasureType.RECHECK, "qc-li");

        var view = releaseService.view(id);
        assertEquals(1, view.targetVersion());
        assertNull(view.release());
        var roll1 = view.rolls().get(0);
        assertEquals(51.0, roll1.original().l());
        assertNull(roll1.recheck());
        assertEquals("ORIGINAL", roll1.adoptedSource());
        assertEquals(SQRT3, roll1.deltaE(), 1e-12);
        assertTrue(roll1.pass());
        var roll2 = view.rolls().get(1);
        assertEquals(53.0, roll2.original().l());
        assertEquals(50.0, roll2.recheck().l());
        assertEquals("RECHECK", roll2.adjudication().decision());
        assertEquals("qc-li", roll2.adjudication().inspector());
        assertEquals("RECHECK", roll2.adoptedSource());
        assertEquals(0.0, roll2.deltaE(), 1e-12);

        releaseService.release(id, "rel-1", null);
        var frozen = releaseService.view(id);
        assertEquals("RELEASED", frozen.status());
        assertEquals(2, frozen.release().items().size());
        assertFalse(frozen.rolls().get(1).deltaE() > 2.0);
    }
}