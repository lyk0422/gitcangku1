package com.example.starter.kiln;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次主流程与失败分支测试（真实 H2，MODE=MySQL）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:kilntest_svc;MODE=MySQL;DB_CLOSE_DELAY=-1")
@Import(TestKilnConfig.class)
class KilnServiceTest {

    @Autowired
    private KilnService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KilnRepository repository;

    @BeforeEach
    void setUp() {
        KilnTestSupport.clean(jdbc);
    }

    private long newBatch() {
        long programId = service.createProgram(KilnTestSupport.standardProgram());
        return service.createBatch(new KilnDtos.CreateBatchRequest("B-" + programId, programId));
    }

    private long newBatchWithFullTrajectory() {
        long batchId = newBatch();
        service.uploadPoints(batchId, KilnTestSupport.upload("up-1", KilnTestSupport.fullTrajectory()));
        return batchId;
    }

    @Test
    void releaseHappyPathLocksProgramAndPointsLimit() {
        long batchId = newBatchWithFullTrajectory();

        KilnDtos.ReleaseResult result = service.release(batchId,
                new KilnDtos.ReleaseRequest("RELEASE", "qc-wang"));

        assertEquals("RELEASED", result.decision());
        assertEquals("v1", result.programVersion());
        assertEquals(37, result.pointsLimit());
        assertFalse(result.replayed());
        assertEquals(3, result.deviations().size());
        assertEquals(0.0, result.deviations().get(0).worstDeviation());

        KilnDtos.BatchDetail detail = service.detail(batchId);
        assertEquals("RELEASED", detail.status());
        assertEquals("v1", detail.programVersion());
        assertEquals(37, detail.segments().stream().mapToInt(KilnDtos.SegmentView::sampleCount).sum());
        assertEquals(0.0, detail.segments().get(1).worstDeviation());
        assertNotNull(detail.release());
        assertEquals("qc-wang", detail.release().inspector());
    }

    @Test
    void eightMinuteGapRejectedThenBackfillAllowsRelease() {
        long batchId = newBatch();
        List<KilnDtos.PointInput> points = new ArrayList<>(KilnTestSupport.fullTrajectory());
        // 移除 60 分钟点，改在 63 分钟补一个点，制造 55 -> 63 的 8 分钟空档
        points.removeIf(p -> p.measuredAt().equals(KilnTestSupport.BASE.plus(60, ChronoUnit.MINUTES)));
        points.add(new KilnDtos.PointInput(KilnTestSupport.BASE.plus(63, ChronoUnit.MINUTES),
                KilnTestSupport.targetAt(KilnTestSupport.BASE.plus(63, ChronoUnit.MINUTES))));
        service.uploadPoints(batchId, KilnTestSupport.upload("up-gap", points));

        KilnException error = assertThrows(KilnException.class, () -> service.release(batchId,
                new KilnDtos.ReleaseRequest("RELEASE", "qc-wang")));
        assertEquals("RELEASE_CHECK_FAILED", error.code());
        @SuppressWarnings("unchecked")
        List<KilnDtos.Violation> violations = (List<KilnDtos.Violation>) error.details();
        assertEquals(1, violations.size());
        assertEquals("SAMPLING_GAP", violations.get(0).type());
        assertEquals(1, violations.get(0).segmentSeq());
        assertEquals(KilnTestSupport.BASE.plus(55, ChronoUnit.MINUTES), violations.get(0).time());

        // 批次未驳回，补录 58 分钟合法点后重新检查可通过
        service.uploadPoints(batchId, KilnTestSupport.upload("up-fix", List.of(
                new KilnDtos.PointInput(KilnTestSupport.BASE.plus(58, ChronoUnit.MINUTES),
                        KilnTestSupport.targetAt(KilnTestSupport.BASE.plus(58, ChronoUnit.MINUTES))))));
        KilnDtos.ReleaseResult result = service.release(batchId,
                new KilnDtos.ReleaseRequest("RELEASE", "qc-wang"));
        assertEquals("RELEASED", result.decision());
        assertEquals(38, result.pointsLimit());
    }

    @Test
    void outOfTolerancePointRejectedWithSegmentAndTime() {
        long batchId = newBatch();
        List<KilnDtos.PointInput> points = new ArrayList<>(KilnTestSupport.fullTrajectory());
        points.removeIf(p -> p.measuredAt().equals(KilnTestSupport.BASE.plus(30, ChronoUnit.MINUTES)));
        points.add(new KilnDtos.PointInput(KilnTestSupport.BASE.plus(30, ChronoUnit.MINUTES), 830.0));
        service.uploadPoints(batchId, KilnTestSupport.upload("up-hot", points));

        KilnException error = assertThrows(KilnException.class, () -> service.release(batchId,
                new KilnDtos.ReleaseRequest("RELEASE", "qc-li")));
        assertEquals("RELEASE_CHECK_FAILED", error.code());
        @SuppressWarnings("unchecked")
        List<KilnDtos.Violation> violations = (List<KilnDtos.Violation>) error.details();
        assertEquals("OUT_OF_TOLERANCE", violations.get(0).type());
        assertEquals(1, violations.get(0).segmentSeq());
        assertEquals(KilnTestSupport.BASE.plus(30, ChronoUnit.MINUTES), violations.get(0).time());
    }

    @Test
    void missingBoundarySamplesRejected() {
        long batchId = newBatch();
        List<KilnDtos.PointInput> points = new ArrayList<>(KilnTestSupport.fullTrajectory());
        points.removeIf(p -> p.measuredAt().equals(KilnTestSupport.BASE));
        points.removeIf(p -> p.measuredAt().equals(KilnTestSupport.BASE.plus(3, ChronoUnit.HOURS)));
        service.uploadPoints(batchId, KilnTestSupport.upload("up-edge", points));

        KilnException error = assertThrows(KilnException.class, () -> service.release(batchId,
                new KilnDtos.ReleaseRequest("RELEASE", "qc-li")));
        @SuppressWarnings("unchecked")
        List<KilnDtos.Violation> violations = (List<KilnDtos.Violation>) error.details();
        List<String> types = violations.stream().map(KilnDtos.Violation::type).toList();
        assertTrue(types.contains("MISSING_START_BOUNDARY"));
        assertTrue(types.contains("MISSING_END_BOUNDARY"));
    }
    @Test
    void duplicateTimestampUploadRejected() {
        long batchId = newBatch();
        List<KilnDtos.PointInput> points = new ArrayList<>(KilnTestSupport.fullTrajectory());
        service.uploadPoints(batchId, KilnTestSupport.upload("up-1", points));

        KilnException error = assertThrows(KilnException.class, () -> service.uploadPoints(batchId,
                KilnTestSupport.upload("up-2", List.of(new KilnDtos.PointInput(KilnTestSupport.BASE, 801.0)))));
        assertEquals("DUPLICATE_TIMESTAMP", error.code());

        List<KilnDtos.PointInput> duplicated = List.of(
                new KilnDtos.PointInput(KilnTestSupport.BASE.plus(10, ChronoUnit.MINUTES), 800.0),
                new KilnDtos.PointInput(KilnTestSupport.BASE.plus(10, ChronoUnit.MINUTES), 801.0));
        KilnException sameRequest = assertThrows(KilnException.class, () -> service.uploadPoints(batchId,
                KilnTestSupport.upload("up-3", duplicated)));
        assertEquals("DUPLICATE_TIMESTAMP", sameRequest.code());
        // 重复时间戳写入整体回滚，不得残留部分点
        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_temp_point WHERE batch_id = ? AND request_id = 'up-3'",
                Integer.class, batchId);
        assertEquals(0, remaining);
    }

    @Test
    void uploadIdempotentReplayAndConflict() {
        long batchId = newBatch();
        List<KilnDtos.PointInput> points = List.of(
                new KilnDtos.PointInput(KilnTestSupport.BASE, 800.0),
                new KilnDtos.PointInput(KilnTestSupport.BASE.plus(5, ChronoUnit.MINUTES), 801.0));

        KilnDtos.UploadResult first = service.uploadPoints(batchId, KilnTestSupport.upload("req-9", points));
        assertFalse(first.replayed());
        assertEquals(2, first.inserted());

        KilnDtos.UploadResult replay = service.uploadPoints(batchId, KilnTestSupport.upload("req-9", points));
        assertTrue(replay.replayed());
        assertEquals(2, replay.inserted());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_temp_point WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(2, count);

        KilnException conflict = assertThrows(KilnException.class, () -> service.uploadPoints(batchId,
                KilnTestSupport.upload("req-9", List.of(
                        new KilnDtos.PointInput(KilnTestSupport.BASE, 999.0)))));
        assertEquals("REQUEST_ID_CONFLICT", conflict.code());
        assertEquals(409, conflict.status().value());
    }

    @Test
    void anomalyMarkedBatchCanOnlyBeRejectedOrRefired() {
        long batchId = newBatchWithFullTrajectory();
        service.markAnomaly(batchId, new KilnDtos.MarkAnomalyRequest("热电偶漂移", "op-zhao"));

        KilnException error = assertThrows(KilnException.class, () -> service.release(batchId,
                new KilnDtos.ReleaseRequest("RELEASE", "qc-wang")));
        assertEquals("BATCH_HAS_ANOMALY", error.code());

        KilnDtos.ReleaseResult rejected = service.release(batchId,
                new KilnDtos.ReleaseRequest("REJECT", "qc-wang"));
        assertEquals("REJECTED", rejected.decision());

        // 驳回后不能补录点再直接放行
        KilnException uploadAfterReject = assertThrows(KilnException.class, () -> service.uploadPoints(
                batchId, KilnTestSupport.upload("up-late", List.of(
                        new KilnDtos.PointInput(KilnTestSupport.BASE.plus(7, ChronoUnit.MINUTES), 800.0)))));
        assertEquals("BATCH_CLOSED", uploadAfterReject.code());
        KilnDtos.ReleaseResult again = service.release(batchId,
                new KilnDtos.ReleaseRequest("RELEASE", "qc-wang"));
        assertEquals("REJECTED", again.decision());
        assertTrue(again.replayed());

        // 原始测温数据未被覆盖
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_temp_point WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(37, count);

        // 复烧产生独立批次，沿用锁定的程序版本
        long refireId = service.refire(batchId);
        KilnDtos.BatchDetail refire = service.detail(refireId);
        assertEquals(batchId, refire.refireOf());
        assertEquals("FIRING", refire.status());
        assertEquals("v1", refire.programVersion());
        assertNull(refire.release());
    }

    @Test
    void refireRequiresAnomalyMark() {
        long batchId = newBatch();
        KilnException error = assertThrows(KilnException.class, () -> service.refire(batchId));
        assertEquals("REFIRE_REQUIRES_ANOMALY", error.code());
    }

    @Test
    void releaseIsIdempotentAndSingleRecord() {
        long batchId = newBatchWithFullTrajectory();
        KilnDtos.ReleaseResult first = service.release(batchId,
                new KilnDtos.ReleaseRequest("RELEASE", "qc-wang"));
        KilnDtos.ReleaseResult second = service.release(batchId,
                new KilnDtos.ReleaseRequest("RELEASE", "qc-other"));
        assertTrue(second.replayed());
        assertEquals(first.recordId(), second.recordId());
        assertEquals("qc-wang", second.inspector());
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_release_record WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(1, records);
    }

    @Test
    void nonContiguousSegmentsRejected() {
        KilnDtos.CreateProgramRequest broken = new KilnDtos.CreateProgramRequest("BAD", "v1", List.of(
                new KilnDtos.SegmentInput(KilnTestSupport.BASE, KilnTestSupport.BASE.plus(1, ChronoUnit.HOURS), 800.0, 20.0),
                new KilnDtos.SegmentInput(KilnTestSupport.BASE.plus(2, ChronoUnit.HOURS), KilnTestSupport.BASE.plus(3, ChronoUnit.HOURS), 900.0, 20.0)));
        KilnException error = assertThrows(KilnException.class, () -> service.createProgram(broken));
        assertEquals("SEGMENTS_NOT_CONTIGUOUS", error.code());
    }

    @Test
    void sqlLiteralTimestampsAreReadAsUtc() {
        jdbc.update("INSERT INTO kiln_program (id, name, version, created_at)"
                + " VALUES (99, 'LIT', 'v1', TIMESTAMP '2026-03-01 00:00:00')");
        jdbc.update("INSERT INTO kiln_segment (program_id, seq, start_utc, end_utc, target_temp, tolerance)"
                + " VALUES (99, 1, TIMESTAMP '2026-03-01 00:00:00', TIMESTAMP '2026-03-01 01:00:00', 800.0, 20.0)");

        List<KilnRepository.SegmentRow> segments = repository.segmentsOf(99);

        assertEquals(java.time.Instant.parse("2026-03-01T00:00:00Z"), segments.get(0).startUtc());
        assertEquals(java.time.Instant.parse("2026-03-01T01:00:00Z"), segments.get(0).endUtc());
    }

    @Test
    void releasedBatchRejectsFurtherPoints() {
        long batchId = newBatchWithFullTrajectory();
        service.release(batchId, new KilnDtos.ReleaseRequest("RELEASE", "qc-wang"));
        KilnException error = assertThrows(KilnException.class, () -> service.uploadPoints(batchId,
                KilnTestSupport.upload("up-late", List.of(
                        new KilnDtos.PointInput(KilnTestSupport.BASE.plus(4, ChronoUnit.HOURS), 900.0)))));
        assertEquals("BATCH_CLOSED", error.code());
    }
}