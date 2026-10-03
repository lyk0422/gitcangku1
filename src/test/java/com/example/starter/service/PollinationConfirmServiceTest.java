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
 * 撤销、结果确认与时间线测试（真实 H2，可控时钟）。
 */
class PollinationConfirmServiceTest extends ServiceTestBase {

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
    void revokeUnconfirmedAllowsRepollinationOnlyWithinWindow() {
        InflorescenceView flower = newInflorescence("INF-11", "2026-02-01T10:00:00Z");
        newBatch("PB-11");
        OperationView created = service.createOperation(flower.id(), op("req-11", "PB-11", "tech-a"));

        OperationView revoked = service.revokeOperation(created.id());
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isEqualTo(T0);

        clock.set(Instant.parse("2026-02-02T09:59:00Z"));
        OperationView redone = service.createOperation(flower.id(), op("req-12", "PB-11", "tech-b"));
        assertThat(redone.status()).isEqualTo("EFFECTIVE");

        service.revokeOperation(redone.id());
        clock.set(Instant.parse("2026-02-02T10:00:00Z"));
        expectBusiness(() -> service.createOperation(flower.id(), op("req-13", "PB-11", "tech-b")),
                HttpStatus.UNPROCESSABLE_ENTITY, "WINDOW_EXPIRED");
    }

    @Test
    void confirmedSuccessFreezesBatchNoAndCannotBeRevoked() {
        InflorescenceView flower = newInflorescence("INF-12", "2026-02-01T10:00:00Z");
        newBatch("PB-12");
        OperationView created = service.createOperation(flower.id(), op("req-14", "PB-12", "tech-a"));

        clock.set(Instant.parse("2026-02-03T10:00:00Z"));
        OperationView confirmed = service.confirmOperation(created.id(),
                new ConfirmOperationRequest("cfm-1", ConfirmResult.SUCCESS));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED_SUCCESS");
        assertThat(confirmed.confirmedBatchNo()).isEqualTo("PB-12");
        assertThat(confirmed.confirmedAt()).isEqualTo(Instant.parse("2026-02-03T10:00:00Z"));

        expectBusiness(() -> service.revokeOperation(created.id()),
                HttpStatus.CONFLICT, "OPERATION_ALREADY_CONFIRMED");

        service.disablePollenBatch("PB-12");
        service.removeInflorescence(flower.id());
        TimelineView timeline = service.getTimeline(flower.id());
        assertThat(timeline.operations()).hasSize(1);
        assertThat(timeline.operations().get(0).confirmedBatchNo()).isEqualTo("PB-12");
        assertThat(timeline.inflorescence().removedAt()).isNotNull();
    }

    @Test
    void confirmationAfterSeventyTwoHoursRejected() {
        InflorescenceView flower = newInflorescence("INF-13", "2026-02-01T10:00:00Z");
        newBatch("PB-13");
        OperationView created = service.createOperation(flower.id(), op("req-15", "PB-13", "tech-a"));

        clock.set(Instant.parse("2026-02-04T10:00:01Z"));
        expectBusiness(() -> service.confirmOperation(created.id(),
                new ConfirmOperationRequest("cfm-2", ConfirmResult.SUCCESS)),
                HttpStatus.UNPROCESSABLE_ENTITY, "CONFIRMATION_EXPIRED");

        OperationView boundary = service.createOperation(
                newInflorescence("INF-14", "2026-02-04T10:00:01Z").id(), op("req-16", "PB-13", "tech-a"));
        clock.set(Instant.parse("2026-02-07T10:00:01Z"));
        OperationView confirmed = service.confirmOperation(boundary.id(),
                new ConfirmOperationRequest("cfm-3", ConfirmResult.SUCCESS));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED_SUCCESS");
    }

    @Test
    void confirmedFailureFreesInflorescenceForRepollination() {
        InflorescenceView flower = newInflorescence("INF-15", "2026-02-01T10:00:00Z");
        newBatch("PB-15");
        OperationView created = service.createOperation(flower.id(), op("req-17", "PB-15", "tech-a"));

        OperationView failed = service.confirmOperation(created.id(),
                new ConfirmOperationRequest("cfm-4", ConfirmResult.FAILED));
        assertThat(failed.status()).isEqualTo("CONFIRMED_FAILED");
        assertThat(failed.confirmedBatchNo()).isNull();

        OperationView redone = service.createOperation(flower.id(), op("req-18", "PB-15", "tech-b"));
        assertThat(redone.status()).isEqualTo("EFFECTIVE");
        assertThat(countOperations(flower.id(), "EFFECTIVE")).isEqualTo(1);
    }

    @Test
    void confirmationIdempotencyAndConflicts() {
        InflorescenceView flower = newInflorescence("INF-16", "2026-02-01T10:00:00Z");
        newBatch("PB-16");
        OperationView created = service.createOperation(flower.id(), op("req-19", "PB-16", "tech-a"));

        OperationView confirmed = service.confirmOperation(created.id(),
                new ConfirmOperationRequest("cfm-5", ConfirmResult.SUCCESS));
        OperationView replayed = service.confirmOperation(created.id(),
                new ConfirmOperationRequest("cfm-5", ConfirmResult.SUCCESS));
        assertThat(replayed.confirmedAt()).isEqualTo(confirmed.confirmedAt());

        expectBusiness(() -> service.confirmOperation(created.id(),
                new ConfirmOperationRequest("cfm-5", ConfirmResult.FAILED)),
                HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");
        expectBusiness(() -> service.confirmOperation(created.id(),
                new ConfirmOperationRequest("cfm-6", ConfirmResult.FAILED)),
                HttpStatus.CONFLICT, "OPERATION_ALREADY_CONFIRMED");
    }

    @Test
    void confirmOnRevokedOperationConflict() {
        InflorescenceView flower = newInflorescence("INF-17", "2026-02-01T10:00:00Z");
        newBatch("PB-17");
        OperationView created = service.createOperation(flower.id(), op("req-20", "PB-17", "tech-a"));
        service.revokeOperation(created.id());

        expectBusiness(() -> service.confirmOperation(created.id(),
                new ConfirmOperationRequest("cfm-7", ConfirmResult.SUCCESS)),
                HttpStatus.CONFLICT, "OPERATION_ALREADY_REVOKED");
    }

    @Test
    void timelineShowsWindowPollenSourceAndOperations() {
        InflorescenceView flower = newInflorescence("INF-18", "2026-02-01T10:00:00Z");
        newBatch("PB-18");
        OperationView created = service.createOperation(flower.id(), op("req-21", "PB-18", "tech-a"));
        service.confirmOperation(created.id(), new ConfirmOperationRequest("cfm-8", ConfirmResult.SUCCESS));

        TimelineView timeline = service.getTimeline(flower.id());
        assertThat(timeline.inflorescence().firstOpenAt()).isEqualTo(T0);
        assertThat(timeline.inflorescence().windowEndAt()).isEqualTo(Instant.parse("2026-02-02T10:00:00Z"));
        assertThat(timeline.inflorescence().windowOpen()).isTrue();
        assertThat(timeline.operations()).hasSize(1);
        OperationView entry = timeline.operations().get(0);
        assertThat(entry.batchNo()).isEqualTo("PB-18");
        assertThat(entry.technicianId()).isEqualTo("tech-a");
        assertThat(entry.operatedAt()).isEqualTo(T0);
        assertThat(entry.confirmedAt()).isEqualTo(T0);
        assertThat(entry.confirmedBatchNo()).isEqualTo("PB-18");
    }
}