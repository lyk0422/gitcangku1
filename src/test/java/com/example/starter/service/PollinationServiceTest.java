package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.web.ConfirmOperationRequest;
import com.example.starter.web.ConfirmOperationRequest.ConfirmResult;
import com.example.starter.web.CreateOperationRequest;
import com.example.starter.web.InflorescenceView;
import com.example.starter.web.OperationView;
import com.example.starter.web.TimelineView;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 授粉窗口主流程与失败分支测试（真实 H2，可控时钟）。
 */
class PollinationServiceTest extends ServiceTestBase {

    private CreateOperationRequest op(String requestId, String batchNo, String technician) {
        return new CreateOperationRequest(requestId, batchNo, technician);
    }

    private void expectBusiness(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.status()).isEqualTo(status);
                    assertThat(error.code()).isEqualTo(code);
                });
    }

    @Test
    void createOperationRecordsBatchTechnicianAndUtcTime() {
        InflorescenceView flower = newInflorescence("INF-1", "2026-02-01T10:00:00Z");
        newBatch("PB-1");

        OperationView view = service.createOperation(flower.id(), op("req-1", "PB-1", "tech-a"));

        assertThat(view.status()).isEqualTo("EFFECTIVE");
        assertThat(view.batchNo()).isEqualTo("PB-1");
        assertThat(view.technicianId()).isEqualTo("tech-a");
        assertThat(view.operatedAt()).isEqualTo(T0);
        assertThat(countOperations(flower.id(), "EFFECTIVE")).isEqualTo(1);
    }

    @Test
    void windowBoundaryLastMinuteAllowedExactEndRejected() {
        InflorescenceView first = newInflorescence("INF-2", "2026-02-01T10:00:00Z");
        InflorescenceView second = newInflorescence("INF-3", "2026-02-01T10:00:00Z");
        newBatch("PB-2");

        clock.set(Instant.parse("2026-02-02T09:59:00Z"));
        OperationView ok = service.createOperation(first.id(), op("req-2", "PB-2", "tech-a"));
        assertThat(ok.status()).isEqualTo("EFFECTIVE");

        clock.set(Instant.parse("2026-02-02T10:00:00Z"));
        expectBusiness(() -> service.createOperation(second.id(), op("req-3", "PB-2", "tech-a")),
                HttpStatus.UNPROCESSABLE_ENTITY, "WINDOW_EXPIRED");
        assertThat(countOperations(second.id(), "EFFECTIVE")).isZero();
    }

    @Test
    void removedInflorescenceRejectsOperationButKeepsHistory() {
        InflorescenceView flower = newInflorescence("INF-4", "2026-02-01T10:00:00Z");
        newBatch("PB-4");
        OperationView created = service.createOperation(flower.id(), op("req-4", "PB-4", "tech-a"));
        service.revokeOperation(created.id());
        service.removeInflorescence(flower.id());

        expectBusiness(() -> service.createOperation(flower.id(), op("req-5", "PB-4", "tech-b")),
                HttpStatus.UNPROCESSABLE_ENTITY, "INFLORESCENCE_REMOVED");

        TimelineView timeline = service.getTimeline(flower.id());
        assertThat(timeline.inflorescence().removedAt()).isNotNull();
        assertThat(timeline.operations()).hasSize(1);
        assertThat(timeline.operations().get(0).status()).isEqualTo("REVOKED");
    }

    @Test
    void disabledOrUnknownPollenBatchRejected() {
        InflorescenceView flower = newInflorescence("INF-5", "2026-02-01T10:00:00Z");
        newBatch("PB-5");
        service.disablePollenBatch("PB-5");

        expectBusiness(() -> service.createOperation(flower.id(), op("req-6", "PB-5", "tech-a")),
                HttpStatus.UNPROCESSABLE_ENTITY, "POLLEN_BATCH_DISABLED");
        expectBusiness(() -> service.createOperation(flower.id(), op("req-7", "PB-MISSING", "tech-a")),
                HttpStatus.UNPROCESSABLE_ENTITY, "POLLEN_BATCH_UNKNOWN");
        assertThat(countOperations(flower.id(), "EFFECTIVE")).isZero();
    }

    @Test
    void duplicateEffectiveOperationConflict() {
        InflorescenceView flower = newInflorescence("INF-6", "2026-02-01T10:00:00Z");
        newBatch("PB-6");
        service.createOperation(flower.id(), op("req-8", "PB-6", "tech-a"));

        expectBusiness(() -> service.createOperation(flower.id(), op("req-9", "PB-6", "tech-b")),
                HttpStatus.CONFLICT, "DUPLICATE_EFFECTIVE_OPERATION");
        assertThat(countOperations(flower.id(), "EFFECTIVE")).isEqualTo(1);
    }

    @Test
    void idempotentReplaySameParamsReturnsOriginalDifferentParamsConflict() {
        InflorescenceView flower = newInflorescence("INF-7", "2026-02-01T10:00:00Z");
        newBatch("PB-7");
        OperationView created = service.createOperation(flower.id(), op("req-10", "PB-7", "tech-a"));

        OperationView replayed = service.createOperation(flower.id(), op("req-10", "PB-7", "tech-a"));
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(countOperations(flower.id(), "EFFECTIVE")).isEqualTo(1);

        expectBusiness(() -> service.createOperation(flower.id(), op("req-10", "PB-7", "tech-b")),
                HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");
    }
}