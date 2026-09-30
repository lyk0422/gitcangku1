package com.example.starter.maintenance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.api.dto.WitnessReadingRequest;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.WitnessRevision;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 事务业务规则单元测试：Mockito 隔离持久层，时间使用可控时钟。
 * 重点验证大幅修订阈值判定、待确认/见证状态转换与 422 失败分支。
 */
@ExtendWith(MockitoExtension.class)
class EquipmentTxServiceUnitTests {

    /** 可在测试中拨动的固定时钟。 */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final String EQUIPMENT_ID = "eq-u";
    private static final String READING_ID = "r1";
    private static final Instant SAMPLED_AT = Instant.parse("2026-03-01T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-01T09:00:00Z");

    @Mock
    private EquipmentRepository repository;

    private MutableClock clock;
    private EquipmentTxService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T1);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        IdempotencyService idempotency = new IdempotencyService(repository, objectMapper, clock);
        service = new EquipmentTxService(repository, idempotency, clock);
        org.mockito.Mockito.lenient().when(repository.findIdempotency(any()))
                .thenReturn(Optional.empty());
    }

    private Equipment equipment(long version, long threshold) {
        return new Equipment(EQUIPMENT_ID, 6500L, threshold, version);
    }

    private Reading reading(long cumulativeMinutes, int revisionNo) {
        return new Reading(EQUIPMENT_ID, READING_ID, SAMPLED_AT, cumulativeMinutes, revisionNo);
    }

    private void prepareRevision(long threshold, long currentValue, int revisionNo, long version) {
        when(repository.findEquipmentForUpdate(EQUIPMENT_ID))
                .thenReturn(Optional.of(equipment(version, threshold)));
        when(repository.findReading(EQUIPMENT_ID, READING_ID)).thenReturn(
                Optional.of(reading(currentValue, revisionNo)));
        when(repository.existsMaintenanceAnchoringReading(EQUIPMENT_ID, READING_ID)).thenReturn(false);
        when(repository.findPendingWitness(EQUIPMENT_ID, READING_ID)).thenReturn(Optional.empty());
        when(repository.findPrevReading(eq(EQUIPMENT_ID), any(Instant.class))).thenReturn(Optional.empty());
        when(repository.findNextReading(eq(EQUIPMENT_ID), any(Instant.class))).thenReturn(Optional.empty());
    }

    @Test
    void register_persistsThresholdFromCurrentConfig() {
        when(repository.findEquipment(EQUIPMENT_ID)).thenReturn(Optional.empty());
        RegisterEquipmentRequest req = new RegisterEquipmentRequest(
                "reg-1", EQUIPMENT_ID, 6500L, 600L);

        var response = service.register(req);

        assertEquals(600L, response.witnessThresholdMinutes());
        verify(repository).insertEquipment(EQUIPMENT_ID, 6500L, 600L, T1);
    }

    @Test
    void revise_largeDifference_becomesPendingAndDoesNotTouchEffectiveReading() {
        prepareRevision(600L, 6000L, 1, 2L);
        ReviseReadingRequest req = new ReviseReadingRequest(
                "rev-1", 2L, 6720L, "alice");

        ReadingResponse response = service.reviseReading(EQUIPMENT_ID, READING_ID, req);

        // 有效读数仍是 100h（6000），待确认值 112h（6720），差额 720，阈值快照 600
        assertEquals(6000L, response.cumulativeMinutes());
        assertEquals(1, response.revisionNo());
        assertEquals(6720L, response.pendingCumulativeMinutes());
        assertEquals(2, response.pendingRevisionNo());
        assertEquals(720L, response.differenceMinutes());
        assertEquals(600L, response.thresholdMinutes());
        assertEquals("alice", response.submittedBy());

        verify(repository).insertWitness(any(WitnessRevision.class), eq(T1));
        verify(repository, never()).updateReadingValue(any(), any(), anyLong(), anyInt(), any());
        verify(repository, never()).insertRevision(
                any(), any(), anyInt(), anyLong(), any(), any(), any(), any(), any(), any());
        verify(repository).incrementVersion(EQUIPMENT_ID);
    }

    @Test
    void revise_withinThreshold_takesEffectImmediately() {
        prepareRevision(600L, 6000L, 1, 2L);
        ReviseReadingRequest req = new ReviseReadingRequest(
                "rev-2", 2L, 6600L, "alice");

        ReadingResponse response = service.reviseReading(EQUIPMENT_ID, READING_ID, req);

        assertEquals(6600L, response.cumulativeMinutes());
        assertEquals(2, response.revisionNo());
        assertEquals(null, response.pendingCumulativeMinutes());
        verify(repository, never()).insertWitness(any(), any());
        verify(repository).updateReadingValue(EQUIPMENT_ID, READING_ID, 6600L, 2, T1);
        verify(repository).insertRevision(eq(EQUIPMENT_ID), eq(READING_ID), eq(2), eq(6600L),
                eq("rev-2"), eq(null), eq(null), eq(null), eq(null), eq(T1));
    }

    @Test
    void revise_differenceExactlyThreshold_takesEffectImmediately() {
        prepareRevision(600L, 6000L, 1, 2L);
        ReviseReadingRequest req = new ReviseReadingRequest(
                "rev-3", 2L, 6600L, "alice");

        service.reviseReading(EQUIPMENT_ID, READING_ID, req);

        // 差额 600 == 阈值 600：不进待确认
        verify(repository, never()).insertWitness(any(), any());
        verify(repository).updateReadingValue(eq(EQUIPMENT_ID), eq(READING_ID), eq(6600L), eq(2), any());
    }

    @Test
    void revise_backwards_throws422AndWritesNothing() {
        when(repository.findEquipmentForUpdate(EQUIPMENT_ID))
                .thenReturn(Optional.of(equipment(2L, 600L)));
        when(repository.findReading(EQUIPMENT_ID, READING_ID)).thenReturn(
                Optional.of(reading(6000L, 1)));
        when(repository.existsMaintenanceAnchoringReading(EQUIPMENT_ID, READING_ID)).thenReturn(false);
        when(repository.findPendingWitness(EQUIPMENT_ID, READING_ID)).thenReturn(Optional.empty());
        // 前邻有效读数为 6000，修订值 5000 倒退
        when(repository.findPrevReading(eq(EQUIPMENT_ID), any(Instant.class)))
                .thenReturn(Optional.of(reading(6000L, 1)));

        ReviseReadingRequest req = new ReviseReadingRequest(
                "rev-4", 2L, 5000L, "alice");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.reviseReading(EQUIPMENT_ID, READING_ID, req));

        assertEquals(422, ex.status().value());
        assertEquals("READING_ORDER_VIOLATION", ex.code());
        verify(repository, never()).insertWitness(any(), any());
        verify(repository, never()).updateReadingValue(any(), any(), anyLong(), anyInt(), any());
        verify(repository, never()).incrementVersion(any());
    }

    @Test
    void revise_whenPendingExists_throws422() {
        when(repository.findEquipmentForUpdate(EQUIPMENT_ID))
                .thenReturn(Optional.of(equipment(3L, 600L)));
        when(repository.findReading(EQUIPMENT_ID, READING_ID)).thenReturn(
                Optional.of(reading(6000L, 1)));
        when(repository.existsMaintenanceAnchoringReading(EQUIPMENT_ID, READING_ID)).thenReturn(false);
        when(repository.findPendingWitness(EQUIPMENT_ID, READING_ID)).thenReturn(Optional.of(
                new WitnessRevision(EQUIPMENT_ID, READING_ID, 2, 6720L, 600L, 6000L, 720L,
                        "rev-old", "alice", T1)));

        ReviseReadingRequest req = new ReviseReadingRequest(
                "rev-new", 3L, 6050L, "alice");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.reviseReading(EQUIPMENT_ID, READING_ID, req));

        assertEquals(422, ex.status().value());
        assertEquals("READING_PENDING_WITNESS", ex.code());
        verify(repository, never()).insertWitness(any(), any());
        verify(repository, never()).incrementVersion(any());
    }

    @Test
    void witness_sameMaintainer_throws422() {
        WitnessRevision pending = new WitnessRevision(
                EQUIPMENT_ID, READING_ID, 2, 6720L, 600L, 6000L, 720L,
                "rev-1", "alice", T1);
        when(repository.findEquipmentForUpdate(EQUIPMENT_ID))
                .thenReturn(Optional.of(equipment(3L, 600L)));
        when(repository.findPendingWitness(EQUIPMENT_ID, READING_ID)).thenReturn(Optional.of(pending));

        WitnessReadingRequest req = new WitnessReadingRequest(
                "wit-1", 3L, 2, 6720L, "alice");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.witnessReading(EQUIPMENT_ID, READING_ID, req));

        assertEquals(422, ex.status().value());
        assertEquals("WITNESS_SAME_MAINTAINER", ex.code());
        verify(repository, never()).deleteWitness(any(), any());
        verify(repository, never()).incrementVersion(any());
    }

    @Test
    void witness_specifiedVersionConfirmedByDifferentMaintainer_appliesSnapshot() {
        WitnessRevision pending = new WitnessRevision(
                EQUIPMENT_ID, READING_ID, 2, 6720L, 600L, 6000L, 720L,
                "rev-1", "alice", T1);
        when(repository.findEquipmentForUpdate(EQUIPMENT_ID))
                .thenReturn(Optional.of(equipment(3L, 600L)));
        when(repository.findPendingWitness(EQUIPMENT_ID, READING_ID)).thenReturn(Optional.of(pending));
        when(repository.findReading(EQUIPMENT_ID, READING_ID)).thenReturn(
                Optional.of(reading(6000L, 1)));
        when(repository.existsMaintenanceAnchoringReading(EQUIPMENT_ID, READING_ID)).thenReturn(false);
        when(repository.findPrevReading(eq(EQUIPMENT_ID), any(Instant.class))).thenReturn(Optional.empty());
        when(repository.findNextReading(eq(EQUIPMENT_ID), any(Instant.class))).thenReturn(Optional.empty());

        // 见证发生在提交之后：可控时钟前进 10 分钟
        Instant t2 = T1.plus(Duration.ofMinutes(10));
        clock.setInstant(t2);

        WitnessReadingRequest req = new WitnessReadingRequest(
                "wit-1", 3L, 2, 6720L, "bob");
        ReadingResponse response = service.witnessReading(EQUIPMENT_ID, READING_ID, req);

        assertEquals(6720L, response.cumulativeMinutes());
        assertEquals(2, response.revisionNo());
        assertEquals(null, response.pendingCumulativeMinutes());
        verify(repository).updateReadingValue(EQUIPMENT_ID, READING_ID, 6720L, 2, t2);
        // 修订历史保存申请时阈值/差额快照与提交人、见证人；生效时刻为见证时刻
        verify(repository).insertRevision(EQUIPMENT_ID, READING_ID, 2, 6720L,
                "rev-1", 600L, 720L, "alice", "bob", t2);
        verify(repository).deleteWitness(EQUIPMENT_ID, READING_ID);
        verify(repository).incrementVersion(EQUIPMENT_ID);
    }

    @Test
    void witness_staleRevisionNumber_throws422AndApprovesNothing() {
        WitnessRevision pending = new WitnessRevision(
                EQUIPMENT_ID, READING_ID, 3, 6900L, 600L, 6720L, 180L,
                "rev-2", "alice", T1);
        when(repository.findEquipmentForUpdate(EQUIPMENT_ID))
                .thenReturn(Optional.of(equipment(5L, 600L)));
        when(repository.findPendingWitness(EQUIPMENT_ID, READING_ID)).thenReturn(Optional.of(pending));

        // 旧见证请求版本号 2，而当前待生效版本号为 3
        WitnessReadingRequest req = new WitnessReadingRequest(
                "wit-old", 5L, 2, 6720L, "bob");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.witnessReading(EQUIPMENT_ID, READING_ID, req));

        assertEquals(422, ex.status().value());
        assertEquals("WITNESS_VERSION_CHANGED", ex.code());
        verify(repository, never()).updateReadingValue(any(), any(), anyLong(), anyInt(), any());
        verify(repository, never()).deleteWitness(any(), any());
    }

    @Test
    void witness_valueMismatch_throws422() {
        WitnessRevision pending = new WitnessRevision(
                EQUIPMENT_ID, READING_ID, 2, 6720L, 600L, 6000L, 720L,
                "rev-1", "alice", T1);
        when(repository.findEquipmentForUpdate(EQUIPMENT_ID))
                .thenReturn(Optional.of(equipment(3L, 600L)));
        when(repository.findPendingWitness(EQUIPMENT_ID, READING_ID)).thenReturn(Optional.of(pending));

        WitnessReadingRequest req = new WitnessReadingRequest(
                "wit-bad", 3L, 2, 6721L, "bob");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.witnessReading(EQUIPMENT_ID, READING_ID, req));

        assertEquals(422, ex.status().value());
        assertEquals("WITNESS_VALUE_MISMATCH", ex.code());
    }

    @Test
    void status_pendingValueDoesNotParticipateInCycleCalculation() {
        // reading 表只有有效值；待确认值不在其中，故状态按 6000 计算
        when(repository.findEquipment(EQUIPMENT_ID))
                .thenReturn(Optional.of(equipment(3L, 600L)));
        when(repository.findLatestReading(EQUIPMENT_ID))
                .thenReturn(Optional.of(reading(6000L, 1)));
        when(repository.findLastMaintenance(EQUIPMENT_ID)).thenReturn(Optional.empty());

        StatusResponse status = service.getStatus(EQUIPMENT_ID);

        assertEquals(6000L, status.latestCumulativeMinutes());
        assertEquals(6000L, status.runMinutes());
        assertEquals(600L, status.witnessThresholdMinutes());
        // 周期 6500 分钟：6000 < 6500，不触发 DUE
        assertEquals("OK", status.status());
    }
}
