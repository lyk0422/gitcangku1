package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.exception.BusinessException;
import com.example.starter.exception.ConflictException;
import com.example.starter.service.DischargeService;
import com.example.starter.service.view.BatchDetailView;
import com.example.starter.service.view.CellView;
import com.example.starter.service.view.HandoverView;
import com.example.starter.service.view.ReadingView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 电芯放电交接主流程与拒绝分支测试（真实 H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class DischargeServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(3600);

    @Autowired
    private DischargeService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.setInstant(T0);
        jdbc.update("DELETE FROM handover_snapshot");
        jdbc.update("DELETE FROM cell_isolation");
        jdbc.update("DELETE FROM cell_reading");
        jdbc.update("DELETE FROM battery_cell");
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM discharge_batch");
    }

    private String newBatch() {
        String batchNo = "BATCH-" + UUID.randomUUID().toString().substring(0, 8);
        service.createBatch(batchNo);
        return batchNo;
    }

    private CellView enroll(String batchNo, String cellCode, String requestId) {
        return service.enrollCell(batchNo, requestId, cellCode, new BigDecimal("3.70"));
    }

    private ReadingView read(String batchNo, String cellCode, String voltage, String requestId) {
        return service.recordReading(batchNo, requestId, cellCode, new BigDecimal(voltage), T1);
    }

    @Test
    void mainFlow_handoverSucceeds_andSnapshotFrozen() {
        String batchNo = newBatch();
        enroll(batchNo, "CELL-001", "REQ-E1");
        enroll(batchNo, "CELL-002", "REQ-E2");
        enroll(batchNo, "CELL-003", "REQ-E3");
        ReadingView r1 = read(batchNo, "CELL-001", "0.30", "REQ-R1");
        ReadingView r2 = read(batchNo, "CELL-002", "0.40", "REQ-R2");
        ReadingView r3 = read(batchNo, "CELL-003", "0.50", "REQ-R3");

        HandoverView handover = service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B");

        assertThat(handover.status()).isEqualTo("HANDED_OVER");
        assertThat(handover.handoverFrom()).isEqualTo("回收站A");
        assertThat(handover.handoverTo()).isEqualTo("拆解区B");
        assertThat(handover.handoverAt()).isEqualTo(T0);
        assertThat(handover.cells()).hasSize(3);
        assertThat(handover.cells()).extracting("readingId")
                .containsExactlyInAnyOrder(r1.id(), r2.id(), r3.id());

        BatchDetailView detail = service.getBatch(batchNo);
        assertThat(detail.status()).isEqualTo("HANDED_OVER");
        assertThat(detail.cells()).hasSize(3);
        assertThat(detail.cells().get(0).readings()).hasSize(1);
        assertThat(detail.handoverSnapshot()).hasSize(3);
        assertThat(detail.handoverSnapshot()).extracting("cellCode")
                .containsExactlyInAnyOrder("CELL-001", "CELL-002", "CELL-003");
    }

    @Test
    void highVoltageCell_rejectsWholeBatch_thenIsolateAndRehandover() {
        String batchNo = newBatch();
        enroll(batchNo, "CELL-A", "REQ-E1");
        enroll(batchNo, "CELL-B", "REQ-E2");
        enroll(batchNo, "CELL-C", "REQ-E3");
        read(batchNo, "CELL-A", "0.30", "REQ-R1");
        read(batchNo, "CELL-B", "0.60", "REQ-R2");
        read(batchNo, "CELL-C", "0.40", "REQ-R3");

        assertThatThrownBy(() -> service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.cellCodes()).containsExactly("CELL-B");
                    assertThat(e.getMessage()).contains("0.5V");
                });

        CellView isolated = service.isolateCell(batchNo, "CELL-B", "终测电压0.60V超过0.5V上限");
        assertThat(isolated.status()).isEqualTo("ISOLATED");

        HandoverView handover = service.handover(batchNo, "REQ-H2", "回收站A", "拆解区B");
        assertThat(handover.cells()).extracting("cellCode")
                .containsExactlyInAnyOrder("CELL-A", "CELL-C");

        BatchDetailView detail = service.getBatch(batchNo);
        assertThat(detail.cells()).hasSize(3);
        var cellB = detail.cells().stream()
                .filter(c -> c.cell().cellCode().equals("CELL-B")).findFirst().orElseThrow();
        assertThat(cellB.isolation().reason()).isEqualTo("终测电压0.60V超过0.5V上限");
        assertThat(cellB.readings()).hasSize(1);
        assertThat(cellB.readings().get(0).voltage()).isEqualByComparingTo("0.60");
        assertThat(detail.handoverSnapshot()).extracting("cellCode")
                .containsExactlyInAnyOrder("CELL-A", "CELL-C");
    }

    @Test
    void missingReading_rejectsWithCellCode() {
        String batchNo = newBatch();
        enroll(batchNo, "CELL-001", "REQ-E1");
        enroll(batchNo, "CELL-002", "REQ-E2");
        read(batchNo, "CELL-001", "0.30", "REQ-R1");

        assertThatThrownBy(() -> service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.cellCodes()).containsExactly("CELL-002");
                    assertThat(e.getMessage()).contains("缺测");
                });
    }

    @Test
    void readingNotAfterEnrollment_rejects() {
        String batchNo = newBatch();
        enroll(batchNo, "CELL-001", "REQ-E1");
        service.recordReading(batchNo, "REQ-R1", "CELL-001",
                new BigDecimal("0.30"), T0.minusSeconds(60));

        assertThatThrownBy(() -> service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.cellCodes()).containsExactly("CELL-001");
                    assertThat(e.getMessage()).contains("未晚于入批");
                });

        read(batchNo, "CELL-001", "0.30", "REQ-R2");
        HandoverView handover = service.handover(batchNo, "REQ-H2", "回收站A", "拆解区B");
        assertThat(handover.cells()).hasSize(1);
    }
    @Test
    void cellInAnotherOpenBatch_rejected() {
        String batch1 = newBatch();
        String batch2 = newBatch();
        enroll(batch1, "CELL-X", "REQ-E1");

        assertThatThrownBy(() -> service.enrollCell(batch2, "REQ-E2", "CELL-X", new BigDecimal("3.70")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.cellCodes()).containsExactly("CELL-X");
                    assertThat(e.getMessage()).contains(batch1);
                });
    }

    @Test
    void batchCap50_enroll51stRejected() {
        String batchNo = newBatch();
        for (int i = 1; i <= 50; i++) {
            enroll(batchNo, String.format("CELL-%03d", i), "REQ-E" + i);
        }
        assertThatThrownBy(() -> enroll(batchNo, "CELL-051", "REQ-E51"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("50"));
    }

    @Test
    void readingIdempotency_sameParamsReplay_differentParamsConflict() {
        String batchNo = newBatch();
        enroll(batchNo, "CELL-001", "REQ-E1");
        ReadingView first = read(batchNo, "CELL-001", "0.30", "REQ-R1");

        ReadingView replay = read(batchNo, "CELL-001", "0.30", "REQ-R1");
        assertThat(replay.id()).isEqualTo(first.id());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cell_reading", Integer.class);
        assertThat(count).isEqualTo(1);

        assertThatThrownBy(() -> service.recordReading(batchNo, "REQ-R1", "CELL-001",
                new BigDecimal("0.40"), T1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void handoverIdempotency_sameParamsReplay_differentParamsConflict() {
        String batchNo = newBatch();
        enroll(batchNo, "CELL-001", "REQ-E1");
        read(batchNo, "CELL-001", "0.30", "REQ-R1");
        HandoverView first = service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B");

        HandoverView replay = service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B");
        assertThat(replay).isEqualTo(first);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handover_snapshot", Integer.class);
        assertThat(snapshots).isEqualTo(1);

        assertThatThrownBy(() -> service.handover(batchNo, "REQ-H1", "回收站A", "拆解区C"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void handedOverBatch_forbidsRemeasureEnrollVoidAndRehandover() {
        String batchNo = newBatch();
        enroll(batchNo, "CELL-001", "REQ-E1");
        ReadingView reading = read(batchNo, "CELL-001", "0.30", "REQ-R1");
        service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B");

        assertThatThrownBy(() -> service.recordReading(batchNo, "REQ-R2", "CELL-001",
                new BigDecimal("0.10"), T1.plusSeconds(60)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("已交接"));
        assertThatThrownBy(() -> enroll(batchNo, "CELL-002", "REQ-E2"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.voidReading(reading.id()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.handover(batchNo, "REQ-H2", "回收站A", "拆解区B"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("已交接"));

        BatchDetailView detail = service.getBatch(batchNo);
        assertThat(detail.cells().get(0).readings()).hasSize(1);
        assertThat(detail.handoverSnapshot()).hasSize(1);
    }

    @Test
    void voidReading_handoverUsesRemainingValidReading() {
        String batchNo = newBatch();
        enroll(batchNo, "CELL-001", "REQ-E1");
        ReadingView earlier = read(batchNo, "CELL-001", "0.40", "REQ-R1");
        ReadingView later = service.recordReading(batchNo, "REQ-R2", "CELL-001",
                new BigDecimal("0.20"), T1.plusSeconds(60));

        ReadingView voided = service.voidReading(later.id());
        assertThat(voided.status()).isEqualTo("VOID");

        HandoverView handover = service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B");
        assertThat(handover.cells()).hasSize(1);
        assertThat(handover.cells().get(0).readingId()).isEqualTo(earlier.id());

        BatchDetailView detail = service.getBatch(batchNo);
        assertThat(detail.cells().get(0).readings()).hasSize(2);
        assertThat(detail.cells().get(0).readings())
                .extracting("status").containsExactly("VALID", "VOID");
    }

    @Test
    void voidOnlyValidReading_thenHandoverRejectedAsMissing() {
        String batchNo = newBatch();
        enroll(batchNo, "CELL-001", "REQ-E1");
        ReadingView reading = read(batchNo, "CELL-001", "0.30", "REQ-R1");
        service.voidReading(reading.id());

        assertThatThrownBy(() -> service.handover(batchNo, "REQ-H1", "回收站A", "拆解区B"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.cellCodes()).containsExactly("CELL-001");
                    assertThat(e.getMessage()).contains("缺测");
                });
    }
}