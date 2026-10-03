package com.example.starter.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.bridge.dto.ApplyPermitRequest;
import com.example.starter.bridge.dto.TimelineResponse;

/**
 * 许可服务真实 H2 集成测试：主流程、拒绝分支、幂等与状态边界。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class PermitServiceIntegrationTest {

    @Autowired
    private PermitService permitService;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM bridge_permit");
        jdbcTemplate.update("DELETE FROM bridge_rejection");
        clock.set(TestClockConfiguration.BASE_TIME);
    }

    private static ApplyPermitRequest request(String requestId, String plate, int massKg, String plannedAt) {
        return new ApplyPermitRequest(requestId, plate, massKg, Instant.parse(plannedAt));
    }

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    @Test
    void mainFlowLocksWindowThenReleasesOnExit() {
        BridgePermit permit = permitService.apply(request("req-a", "CAR-34T", 34000, "2026-10-04T09:00:00Z"));
        assertThat(permit.status()).isEqualTo(PermitStatus.APPROVED);
        assertThat(permit.windowStartAt()).isEqualTo(at("2026-10-04T09:00:00Z"));
        assertThat(permit.windowEndAt()).isEqualTo(at("2026-10-04T09:10:00Z"));

        clock.set(at("2026-10-04T09:02:00Z"));
        BridgePermit entered = permitService.confirmEntry(permit.id(), "op-entry-1");
        assertThat(entered.status()).isEqualTo(PermitStatus.ENTERED);
        assertThat(entered.actualEntryAt()).isEqualTo(at("2026-10-04T09:02:00Z"));

        clock.set(at("2026-10-04T09:12:00Z"));
        BridgePermit exited = permitService.confirmExit(permit.id(), "op-exit-1");
        assertThat(exited.status()).isEqualTo(PermitStatus.EXITED);
        assertThat(exited.actualExitAt()).isEqualTo(at("2026-10-04T09:12:00Z"));

        TimelineResponse timeline = permitService.timeline();
        assertThat(timeline.maxTotalMassKg()).isEqualTo(35000);
        assertThat(timeline.crossingMinutes()).isEqualTo(10);
        assertThat(timeline.entries()).hasSize(1);
        TimelineResponse.Entry entry = timeline.entries().get(0);
        assertThat(entry.occupiedFrom()).isEqualTo(at("2026-10-04T09:02:00Z"));
        assertThat(entry.occupiedTo()).isEqualTo(at("2026-10-04T09:12:00Z"));
    }

    @Test
    void overweightRejectedAndRequestKeyStaysReusable() {
        assertThatThrownBy(() -> permitService.apply(request("req-fat", "CAR-36T", 36000, "2026-10-04T09:00:00Z")))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("OVERWEIGHT"));
        assertThat(permitService.findByRequestId("req-fat")).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bridge_rejection WHERE reason_code = 'OVERWEIGHT'", Integer.class))
                .isEqualTo(1);

        BridgePermit retried = permitService.apply(request("req-fat", "CAR-34T", 34000, "2026-10-04T09:00:00Z"));
        assertThat(retried.status()).isEqualTo(PermitStatus.APPROVED);
    }

    @Test
    void timeRegressionRejected() {
        assertThatThrownBy(() -> permitService.apply(request("req-past", "CAR-A", 30000, "2026-10-04T08:59:00Z")))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("TIME_REGRESSION"));
        assertThat(permitService.findByRequestId("req-past")).isEmpty();
    }

    @Test
    void overlappingWindowRejectedButAdjacentWindowAllowed() {
        permitService.apply(request("req-a", "CAR-A", 30000, "2026-10-04T09:00:00Z"));
        assertThatThrownBy(() -> permitService.apply(request("req-b", "CAR-B", 30000, "2026-10-04T09:05:00Z")))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("WINDOW_OVERLAP"));

        BridgePermit adjacent = permitService.apply(request("req-c", "CAR-C", 30000, "2026-10-04T09:10:00Z"));
        assertThat(adjacent.status()).isEqualTo(PermitStatus.APPROVED);
    }

    @Test
    void entryOnlyAllowedWithinPermitWindow() {
        BridgePermit permit = permitService.apply(request("req-a", "CAR-A", 30000, "2026-10-04T09:00:00Z"));

        clock.set(at("2026-10-04T08:59:59Z"));
        assertThatThrownBy(() -> permitService.confirmEntry(permit.id(), "op-early"))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("OUTSIDE_WINDOW"));

        clock.set(at("2026-10-04T09:10:00Z"));
        assertThatThrownBy(() -> permitService.confirmEntry(permit.id(), "op-late"))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("OUTSIDE_WINDOW"));

        clock.set(at("2026-10-04T09:05:00Z"));
        assertThat(permitService.confirmEntry(permit.id(), "op-ok").status()).isEqualTo(PermitStatus.ENTERED);
    }
    @Test
    void enteredPermitOccupiesBridgeUntilActualExit() {
        BridgePermit permit = permitService.apply(request("req-a", "CAR-A", 34000, "2026-10-04T09:00:00Z"));
        clock.set(at("2026-10-04T09:05:00Z"));
        permitService.confirmEntry(permit.id(), "op-entry");

        clock.set(at("2026-10-04T09:15:00Z"));
        assertThatThrownBy(() -> permitService.apply(request("req-b", "CAR-B", 30000, "2026-10-04T09:15:00Z")))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("WINDOW_OVERLAP"));

        clock.set(at("2026-10-04T09:20:00Z"));
        permitService.confirmExit(permit.id(), "op-exit");
        BridgePermit next = permitService.apply(request("req-c", "CAR-C", 30000, "2026-10-04T09:20:00Z"));
        assertThat(next.status()).isEqualTo(PermitStatus.APPROVED);
    }

    @Test
    void cancelReleasesWindowAndEnteredPermitIsNotCancellable() {
        BridgePermit permit = permitService.apply(request("req-a", "CAR-A", 30000, "2026-10-04T09:00:00Z"));
        BridgePermit cancelled = permitService.cancel(permit.id(), "op-cancel");
        assertThat(cancelled.status()).isEqualTo(PermitStatus.CANCELLED);

        BridgePermit reused = permitService.apply(request("req-b", "CAR-B", 30000, "2026-10-04T09:00:00Z"));
        assertThat(reused.status()).isEqualTo(PermitStatus.APPROVED);

        clock.set(at("2026-10-04T09:01:00Z"));
        permitService.confirmEntry(reused.id(), "op-entry");
        assertThatThrownBy(() -> permitService.cancel(reused.id(), "op-cancel-2"))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("NOT_CANCELLABLE"));
    }

    @Test
    void idempotentApplyReplayAndParamConflict() {
        BridgePermit permit = permitService.apply(request("req-a", "CAR-A", 30000, "2026-10-04T09:00:00Z"));
        BridgePermit replay = permitService.apply(request("req-a", "CAR-A", 30000, "2026-10-04T09:00:00Z"));
        assertThat(replay.id()).isEqualTo(permit.id());

        assertThatThrownBy(() -> permitService.apply(request("req-a", "CAR-B", 31000, "2026-10-04T09:00:00Z")))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bridge_permit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void entryAndExitReplayKeepsOriginalTimestamps() {
        BridgePermit permit = permitService.apply(request("req-a", "CAR-A", 30000, "2026-10-04T09:00:00Z"));
        clock.set(at("2026-10-04T09:02:00Z"));
        permitService.confirmEntry(permit.id(), "op-entry");

        clock.set(at("2026-10-04T09:03:00Z"));
        BridgePermit replayed = permitService.confirmEntry(permit.id(), "op-entry");
        assertThat(replayed.actualEntryAt()).isEqualTo(at("2026-10-04T09:02:00Z"));
        assertThatThrownBy(() -> permitService.confirmEntry(permit.id(), "op-entry-other"))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));

        clock.set(at("2026-10-04T09:12:00Z"));
        permitService.confirmExit(permit.id(), "op-exit");
        clock.set(at("2026-10-04T09:13:00Z"));
        BridgePermit exitReplayed = permitService.confirmExit(permit.id(), "op-exit");
        assertThat(exitReplayed.actualExitAt()).isEqualTo(at("2026-10-04T09:12:00Z"));
        assertThatThrownBy(() -> permitService.confirmExit(permit.id(), "op-exit-other"))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void exitRequiresEntryAndRejectsBackwardClock() {
        BridgePermit permit = permitService.apply(request("req-a", "CAR-A", 30000, "2026-10-04T09:00:00Z"));
        assertThatThrownBy(() -> permitService.confirmExit(permit.id(), "op-exit"))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_STATE"));

        clock.set(at("2026-10-04T09:05:00Z"));
        permitService.confirmEntry(permit.id(), "op-entry");
        clock.set(at("2026-10-04T09:04:00Z"));
        assertThatThrownBy(() -> permitService.confirmExit(permit.id(), "op-exit"))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("TIME_REGRESSION"));
    }

    @Test
    void cancelReplayAndUnknownPermit() {
        BridgePermit permit = permitService.apply(request("req-a", "CAR-A", 30000, "2026-10-04T09:00:00Z"));
        permitService.cancel(permit.id(), "op-cancel");
        assertThat(permitService.cancel(permit.id(), "op-cancel").status()).isEqualTo(PermitStatus.CANCELLED);
        assertThatThrownBy(() -> permitService.cancel(permit.id(), "op-cancel-other"))
                .isInstanceOfSatisfying(PermitConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(() -> permitService.getPermit(999999L))
                .isInstanceOf(PermitNotFoundException.class);
    }
}