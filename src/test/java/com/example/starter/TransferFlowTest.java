package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.starter.service.AttachmentService;
import com.example.starter.service.BusinessException;
import com.example.starter.service.TagService;
import com.example.starter.service.TransferService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 交接主流程与拒绝分支：发起/确认/取消、目标站点匹配、已确认不得取消。
 */
class TransferFlowTest extends AbstractDatabaseTest {

    @Autowired
    private TagService tagService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private TransferService transferService;

    private String rid() {
        return UUID.randomUUID().toString();
    }

    private void detachedTagAtStationA() {
        tagService.register("T1", "ST-A");
        attachmentService.attach(rid(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T08:00:00Z"));
        attachmentService.detach("T1", "ST-A", Instant.parse("2026-10-04T10:00:00Z"));
    }

    @Test
    void fullChainDetachTransferConfirmAndReattach() {
        detachedTagAtStationA();
        var transfer = transferService.initiate(rid(), "T1", "ST-A", "ST-B",
                Instant.parse("2026-10-04T10:00:00Z"));
        assertEquals("PENDING", transfer.status());

        var confirmed = transferService.confirm(transfer.id(), "ST-B",
                Instant.parse("2026-10-04T10:05:00Z"));
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(Instant.parse("2026-10-04T10:05:00Z"), confirmed.confirmedAt());
        assertEquals("ST-B", tagService.get("T1").stationCode());

        var attached = attachmentService.attach(rid(), "T1", "AN-2", "ST-B",
                Instant.parse("2026-10-04T10:10:00Z"));
        assertEquals("ST-B", attached.stationCode());

        var e = assertThrows(BusinessException.class, () -> attachmentService.attach(
                rid(), "T1", "AN-3", "ST-A", Instant.parse("2026-10-04T10:20:00Z")));
        assertEquals(409, e.status());
    }

    @Test
    void initiateWhileAttachedRejected409() {
        tagService.register("T1", "ST-A");
        attachmentService.attach(rid(), "T1", "AN-1", "ST-A",
                Instant.parse("2026-10-04T08:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> transferService.initiate(
                rid(), "T1", "ST-A", "ST-B", Instant.parse("2026-10-04T09:00:00Z")));
        assertEquals(409, e.status());
        assertEquals("TAG_NOT_DETACHED", e.code());
    }

    @Test
    void initiateByNonHolderRejected409() {
        detachedTagAtStationA();
        var e = assertThrows(BusinessException.class, () -> transferService.initiate(
                rid(), "T1", "ST-B", "ST-C", Instant.parse("2026-10-04T10:00:00Z")));
        assertEquals(409, e.status());
        assertEquals("NOT_CURRENT_HOLDER", e.code());
    }

    @Test
    void confirmByWrongStationRejected409() {
        detachedTagAtStationA();
        var transfer = transferService.initiate(rid(), "T1", "ST-A", "ST-B",
                Instant.parse("2026-10-04T10:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> transferService.confirm(
                transfer.id(), "ST-C", Instant.parse("2026-10-04T10:05:00Z")));
        assertEquals(409, e.status());
        assertEquals("TRANSFER_TARGET_MISMATCH", e.code());
        assertEquals("ST-A", tagService.get("T1").stationCode());
    }

    @Test
    void cancelPendingRestoresOriginalHolder() {
        detachedTagAtStationA();
        var transfer = transferService.initiate(rid(), "T1", "ST-A", "ST-B",
                Instant.parse("2026-10-04T10:00:00Z"));
        var cancelled = transferService.cancel(transfer.id(), "ST-A",
                Instant.parse("2026-10-04T10:05:00Z"));
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("ST-A", tagService.get("T1").stationCode());

        var attached = attachmentService.attach(rid(), "T1", "AN-2", "ST-A",
                Instant.parse("2026-10-04T10:10:00Z"));
        assertEquals("ST-A", attached.stationCode());
    }

    @Test
    void confirmedTransferCannotBeCancelled() {
        detachedTagAtStationA();
        var transfer = transferService.initiate(rid(), "T1", "ST-A", "ST-B",
                Instant.parse("2026-10-04T10:00:00Z"));
        transferService.confirm(transfer.id(), "ST-B", Instant.parse("2026-10-04T10:05:00Z"));
        var e = assertThrows(BusinessException.class, () -> transferService.cancel(
                transfer.id(), "ST-A", Instant.parse("2026-10-04T10:06:00Z")));
        assertEquals(409, e.status());
        assertEquals("TRANSFER_NOT_CANCELLABLE", e.code());
        assertEquals("ST-B", tagService.get("T1").stationCode());
    }

    @Test
    void doubleInitiateRejected409() {
        detachedTagAtStationA();
        transferService.initiate(rid(), "T1", "ST-A", "ST-B",
                Instant.parse("2026-10-04T10:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> transferService.initiate(
                rid(), "T1", "ST-A", "ST-C", Instant.parse("2026-10-04T10:01:00Z")));
        assertEquals(409, e.status());
        assertEquals("TRANSFER_ALREADY_PENDING", e.code());
    }

    @Test
    void confirmTimeRegressionRejected422() {
        detachedTagAtStationA();
        var transfer = transferService.initiate(rid(), "T1", "ST-A", "ST-B",
                Instant.parse("2026-10-04T10:00:00Z"));
        var e = assertThrows(BusinessException.class, () -> transferService.confirm(
                transfer.id(), "ST-B", Instant.parse("2026-10-04T09:59:00Z")));
        assertEquals(422, e.status());
        assertEquals("CONFIRM_TIME_REGRESSION", e.code());
    }
}