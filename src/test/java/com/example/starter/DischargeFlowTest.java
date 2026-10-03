package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.error.ConflictException;
import com.example.starter.error.RejectionException;
import com.example.starter.service.BatchQueryService;
import com.example.starter.service.BatchQueryService.BatchView;
import com.example.starter.web.dto.ApiResponses.HandoverResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 主流程与拒绝分支：真实 H2 验证。 */
class DischargeFlowTest extends BaseIntegrationTest {

    @Autowired
    private BatchQueryService queryService;

    @Test
    void handoverSuccessFreezesSnapshot() {
        String batch = givenBatch();
        for (String cell : new String[] {"C-A1", "C-A2", "C-A3"}) {
            givenCell(cell);
            dischargeService.intake(requestId(), batch, cell, 3.7);
            dischargeService.finalReading(requestId(), batch, cell, 0.4, T0.plusSeconds(3600));
        }
        Instant handoverAt = T0.plusSeconds(7200);
        TestClockConfig.MUTABLE_CLOCK.setInstant(handoverAt);

        HandoverResponse response = dischargeService.handover(requestId(), batch,
                "RECYCLE-STATION", "DISASSEMBLY-ZONE");

        assertEquals(batch, response.batchCode());
        assertEquals("RECYCLE-STATION", response.fromParty());
        assertEquals("DISASSEMBLY-ZONE", response.toParty());
        assertEquals(handoverAt, response.handedOverAt());
        assertEquals(3, response.cells().size());
        assertEquals(1, count("handover"));
        assertEquals(3, count("handover_cell"));
        assertEquals("HANDED_OVER", jdbc.queryForObject(
                "SELECT status FROM discharge_batch WHERE batch_code = ?", String.class, batch));
        for (String cell : new String[] {"C-A1", "C-A2", "C-A3"}) {
            assertEquals("HANDED_OVER", jdbc.queryForObject(
                    "SELECT status FROM battery_cell WHERE cell_code = ?", String.class, cell));
        }
        BatchView view = queryService.query(batch);
        assertNotNull(view.handover());
        assertEquals(response.handoverCode(), view.handover().handoverCode());
        assertEquals(3, view.handover().cells().size());
    }

    @Test
    void rejectWholeBatchWhenOneCellIsAboveLimitThenIsolateAndRehandover() {
        String batch = givenBatch();
        givenCell("C-B1");
        givenCell("C-B2");
        givenCell("C-B3");
        for (String cell : new String[] {"C-B1", "C-B2", "C-B3"}) {
            dischargeService.intake(requestId(), batch, cell, 3.7);
        }
        dischargeService.finalReading(requestId(), batch, "C-B1", 0.4, T0.plusSeconds(3600));
        dischargeService.finalReading(requestId(), batch, "C-B2", 0.6, T0.plusSeconds(3600));
        dischargeService.finalReading(requestId(), batch, "C-B3", 0.3, T0.plusSeconds(3600));

        RejectionException rejection = assertThrows(RejectionException.class,
                () -> dischargeService.handover(requestId(), batch, "RECYCLE-STATION",
                        "DISASSEMBLY-ZONE"));
        assertEquals(java.util.List.of("C-B2"), rejection.cellCodes());
        assertEquals(0, count("handover"));

        dischargeService.isolate(batch, "C-B2", "终测电压 0.6 V 超过 0.5 V 上限");
        assertEquals("ISOLATED", jdbc.queryForObject(
                "SELECT status FROM battery_cell WHERE cell_code = ?", String.class, "C-B2"));

        HandoverResponse response = dischargeService.handover(requestId(), batch,
                "RECYCLE-STATION", "DISASSEMBLY-ZONE");
        assertEquals(2, response.cells().size());
        assertFalse(response.cells().stream().anyMatch(c -> c.cellCode().equals("C-B2")));

        BatchView view = queryService.query(batch);
        var isolated = view.cells().stream().filter(c -> c.cellCode().equals("C-B2")).findFirst()
                .orElseThrow();
        assertEquals("终测电压 0.6 V 超过 0.5 V 上限", isolated.isolationReason());
        assertEquals(2, isolated.readings().size());
        assertEquals(0.6, isolated.readings().get(1).voltage());
    }

    @Test
    void rejectWhenFinalMissing() {
        String batch = givenBatch();
        givenCell("C-C1");
        givenCell("C-C2");
        dischargeService.intake(requestId(), batch, "C-C1", 3.7);
        dischargeService.intake(requestId(), batch, "C-C2", 3.7);
        dischargeService.finalReading(requestId(), batch, "C-C1", 0.4, T0.plusSeconds(3600));

        RejectionException rejection = assertThrows(RejectionException.class,
                () -> dischargeService.handover(requestId(), batch, "A", "B"));
        assertEquals(java.util.List.of("C-C2"), rejection.cellCodes());
    }

    @Test
    void rejectFinalMeasuredNotAfterIntake() {
        String batch = givenBatch();
        givenCell("C-D1");
        dischargeService.intake(requestId(), batch, "C-D1", 3.7);

        assertThrows(RejectionException.class,
                () -> dischargeService.finalReading(requestId(), batch, "C-D1", 0.4, T0));
        assertThrows(RejectionException.class,
                () -> dischargeService.finalReading(requestId(), batch, "C-D1", 0.4,
                        T0.minusSeconds(1)));
    }

    @Test
    void forbidMeasurementAfterHandover() {
        String batch = givenBatch();
        givenCell("C-E1");
        dischargeService.intake(requestId(), batch, "C-E1", 3.7);
        dischargeService.finalReading(requestId(), batch, "C-E1", 0.4, T0.plusSeconds(3600));
        dischargeService.handover(requestId(), batch, "A", "B");

        assertThrows(ConflictException.class,
                () -> dischargeService.finalReading(requestId(), batch, "C-E1", 0.3,
                        T0.plusSeconds(7200)));
        assertThrows(ConflictException.class,
                () -> dischargeService.handover(requestId(), batch, "A", "B"));
        assertEquals(1, count("handover"));
    }

    @Test
    void conflictWhenCellOccupiedByAnotherBatch() {
        String batchA = givenBatch();
        String batchB = givenBatch();
        givenCell("C-F1");
        dischargeService.intake(requestId(), batchA, "C-F1", 3.7);

        assertThrows(ConflictException.class,
                () -> dischargeService.intake(requestId(), batchB, "C-F1", 3.7));
        assertEquals(batchA, jdbc.queryForObject(
                "SELECT batch_code FROM battery_cell WHERE cell_code = ?", String.class, "C-F1"));
    }

    @Test
    void rejectIntakeBeyondFiftyCells() {
        String batch = givenBatch();
        for (int i = 1; i <= 50; i++) {
            String cell = String.format("C-G%02d", i);
            givenCell(cell);
            dischargeService.intake(requestId(), batch, cell, 3.7);
        }
        givenCell("C-G51");
        RejectionException rejection = assertThrows(RejectionException.class,
                () -> dischargeService.intake(requestId(), batch, "C-G51", 3.7));
        assertEquals(java.util.List.of("C-G51"), rejection.cellCodes());
        assertEquals(50, jdbc.queryForObject(
                "SELECT COUNT(*) FROM battery_cell WHERE batch_code = ?", Long.class, batch)
                .longValue());
    }

    @Test
    void invalidatedFinalBlocksHandoverUntilRemeasured() {
        String batch = givenBatch();
        givenCell("C-H1");
        dischargeService.intake(requestId(), batch, "C-H1", 3.7);
        String oldReading = dischargeService.finalReading(requestId(), batch, "C-H1", 0.4,
                T0.plusSeconds(3600)).readingCode();

        dischargeService.invalidateReading(oldReading);
        RejectionException rejection = assertThrows(RejectionException.class,
                () -> dischargeService.handover(requestId(), batch, "A", "B"));
        assertEquals(java.util.List.of("C-H1"), rejection.cellCodes());

        String newReading = dischargeService.finalReading(requestId(), batch, "C-H1", 0.3,
                T0.plusSeconds(5400)).readingCode();
        HandoverResponse response = dischargeService.handover(requestId(), batch, "A", "B");
        assertEquals(newReading, response.cells().get(0).finalReadingCode());

        BatchView view = queryService.query(batch);
        var chain = view.cells().get(0).readings();
        assertEquals(3, chain.size());
        assertFalse(chain.get(1).valid());
        assertTrue(chain.get(2).valid());
    }
}