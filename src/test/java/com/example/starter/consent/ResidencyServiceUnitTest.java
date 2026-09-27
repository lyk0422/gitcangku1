package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.ConsentRepository.RecordRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.EraseRequest;
import com.example.starter.consent.dto.MigrateRequest;
import com.example.starter.consent.dto.MigrateResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RegionBindRequest;
import com.example.starter.consent.dto.RegionRegisterRequest;

/**
 * 驻留与授权服务单元测试：以 Mockito 隔离持久层，覆盖校验失败分支、403/410/422 区分及
 * 成功路径的关键交互顺序，真实 SQL/事务/并发语义由 H2 集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class ResidencyServiceUnitTest {

    private static final Instant T0 = Instant.parse("2026-09-28T12:00:00Z");

    @Mock
    private ConsentRepository consentRepository;
    @Mock
    private ResidencyRepository residencyRepository;
    @Mock
    private IdempotencyRepository idempotencyRepository;

    private IdempotencySupport idempotency;
    private Clock clock;
    private ResidencyService residencyService;
    private ConsentService consentService;

    @BeforeEach
    void setUp() {
        idempotency = new IdempotencySupport(idempotencyRepository,
                new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
                return Optional.empty();
            }

            @Override
            public void storeSuccess(String requestId, String operation, String fingerprint, Object response) {
            }
        };
        clock = Clock.fixed(T0, ZoneOffset.UTC);
        residencyService = new ResidencyService(consentRepository, residencyRepository, idempotency, clock);
        consentService = new ConsentService(consentRepository, residencyRepository, idempotency, clock);
    }

    private GrantRow activeGrant(int epoch, String region, int version) {
        return new GrantRow("subj-a", Purpose.RESEARCH, epoch, GrantStatus.ACTIVE, region, version);
    }

    private RecordRow record(int epoch, String key, String payload, String region, Instant erasedAt) {
        return new RecordRow("subj-a", Purpose.RESEARCH, epoch, key, payload, region, erasedAt);
    }

    // ---------- 区域绑定 ----------

    @Test
    void bindRegionUnknownRegionIsBadRequestAndUnknownEpochIsNotFound() {
        when(consentRepository.findGrant("subj-a", Purpose.RESEARCH, 1))
                .thenReturn(Optional.of(activeGrant(1, null, 1)));
        assertThatThrownBy(() -> residencyService.bindRegion(
                new RegionBindRequest("b-1", "subj-a", Purpose.RESEARCH, 1, "APAC")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));

        when(consentRepository.findGrant("subj-a", Purpose.RESEARCH, 9)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> residencyService.bindRegion(
                new RegionBindRequest("b-2", "subj-a", Purpose.RESEARCH, 9, "EU")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    void bindRegionChangingBoundRegionIsConflict() {
        when(consentRepository.findGrant("subj-a", Purpose.RESEARCH, 1))
                .thenReturn(Optional.of(activeGrant(1, "EU", 1)));
        when(residencyRepository.findRegion("US"))
                .thenReturn(Optional.of(new ResidencyRepository.RegionRow("US", T0)));
        assertThatThrownBy(() -> residencyService.bindRegion(
                new RegionBindRequest("b-1", "subj-a", Purpose.RESEARCH, 1, "US")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("REGION_ALREADY_BOUND");
                });
        verify(consentRepository, never()).bindRegion(any(), any(), anyInt(), any());
    }

    // ---------- 迁移失败分支 ----------

    @Test
    void migrateRevokedSourceIs410() {
        when(consentRepository.findLatestGrantForUpdate("subj-a", Purpose.RESEARCH))
                .thenReturn(Optional.of(new GrantRow("subj-a", Purpose.RESEARCH, 1,
                        GrantStatus.REVOKED, "EU", 1)));
        assertThatThrownBy(() -> residencyService.migrate(new MigrateRequest(
                "m-1", "subj-a", Purpose.RESEARCH, "US", List.of("rec-1"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(410);
                    assertThat(api.getCode()).isEqualTo("MIGRATION_SOURCE_REVOKED");
                });
        verify(consentRepository, never()).insertMigratedGrant(any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void migrateUnregisteredTargetSameRegionAndDuplicateKeysAre422() {
        when(consentRepository.findLatestGrantForUpdate("subj-a", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "EU", 1)));
        when(residencyRepository.findRegion("APAC")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> residencyService.migrate(new MigrateRequest(
                "m-1", "subj-a", Purpose.RESEARCH, "APAC", List.of("rec-1"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("MIGRATION_TARGET_NOT_REGISTERED"));

        when(residencyRepository.findRegion("EU"))
                .thenReturn(Optional.of(new ResidencyRepository.RegionRow("EU", T0)));
        assertThatThrownBy(() -> residencyService.migrate(new MigrateRequest(
                "m-2", "subj-a", Purpose.RESEARCH, "EU", List.of("rec-1"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("MIGRATION_OBJECT_REGION_MISMATCH"));

        when(residencyRepository.findRegion("US"))
                .thenReturn(Optional.of(new ResidencyRepository.RegionRow("US", T0)));
        assertThatThrownBy(() -> residencyService.migrate(new MigrateRequest(
                "m-3", "subj-a", Purpose.RESEARCH, "US", List.of("rec-1", "rec-1"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("MIGRATION_DUPLICATE_OBJECT");
                    assertThat(api.getMessage()).contains("要求数量: 2").contains("差额: 1");
                });
        verify(consentRepository, never()).insertMigratedGrant(any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void migrateMissingErasedAndForeignRegionObjectsAre422() {
        when(consentRepository.findLatestGrantForUpdate("subj-a", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "EU", 1)));
        when(residencyRepository.findRegion("US"))
                .thenReturn(Optional.of(new ResidencyRepository.RegionRow("US", T0)));

        when(consentRepository.findRecords("subj-a", Purpose.RESEARCH, 1, List.of("rec-1", "rec-2")))
                .thenReturn(List.of(record(1, "rec-1", "p1", "EU", null)));
        assertThatThrownBy(() -> residencyService.migrate(new MigrateRequest(
                "m-1", "subj-a", Purpose.RESEARCH, "US", List.of("rec-1", "rec-2"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(422);
                    assertThat(api.getCode()).isEqualTo("MIGRATION_OBJECT_NOT_FOUND");
                    assertThat(api.getMessage()).contains("要求数量: 2").contains("实际数量: 1");
                });

        when(consentRepository.findRecords("subj-a", Purpose.RESEARCH, 1, List.of("rec-1", "rec-2")))
                .thenReturn(List.of(
                        record(1, "rec-1", "p1", "EU", null),
                        record(1, "rec-2", null, "EU", T0)));
        assertThatThrownBy(() -> residencyService.migrate(new MigrateRequest(
                "m-2", "subj-a", Purpose.RESEARCH, "US", List.of("rec-1", "rec-2"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("MIGRATION_OBJECT_ERASED"));

        when(consentRepository.findRecords("subj-a", Purpose.RESEARCH, 1, List.of("rec-1", "rec-2")))
                .thenReturn(List.of(
                        record(1, "rec-1", "p1", "EU", null),
                        record(1, "rec-2", "p2", "US", null)));
        assertThatThrownBy(() -> residencyService.migrate(new MigrateRequest(
                "m-3", "subj-a", Purpose.RESEARCH, "US", List.of("rec-1", "rec-2"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("MIGRATION_OBJECT_REGION_MISMATCH"));
        verify(consentRepository, never()).insertMigratedGrant(any(), any(), anyInt(), anyInt(), any(), any());
        verify(consentRepository, never()).revokeGrant(any(), any(), anyInt());
    }

    @Test
    void migrateSuccessCreatesEpochCopiesObjectsAndRevokesSource() {
        when(consentRepository.findLatestGrantForUpdate("subj-a", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "EU", 1)));
        when(residencyRepository.findRegion("US"))
                .thenReturn(Optional.of(new ResidencyRepository.RegionRow("US", T0)));
        when(consentRepository.findRecords("subj-a", Purpose.RESEARCH, 1, List.of("rec-2", "rec-1")))
                .thenReturn(List.of(
                        record(1, "rec-2", "p2", "EU", null),
                        record(1, "rec-1", "p1", "EU", null)));
        when(consentRepository.revokeGrant("subj-a", Purpose.RESEARCH, 1)).thenReturn(true);

        MigrateResponse response = residencyService.migrate(new MigrateRequest(
                "m-1", "subj-a", Purpose.RESEARCH, "US", List.of("rec-2", "rec-1")));

        assertThat(response.sourceEpoch()).isEqualTo(1);
        assertThat(response.targetEpoch()).isEqualTo(2);
        assertThat(response.requestedCount()).isEqualTo(2);
        assertThat(response.copiedCount()).isEqualTo(2);
        verify(consentRepository).insertMigratedGrant("subj-a", Purpose.RESEARCH, 2, 2, "US", "m-1");
        verify(consentRepository).insertRecord("subj-a", Purpose.RESEARCH, 2, "rec-2", "p2", "US", "m-1");
        verify(consentRepository).insertRecord("subj-a", Purpose.RESEARCH, 2, "rec-1", "p1", "US", "m-1");
        verify(consentRepository).revokeGrant("subj-a", Purpose.RESEARCH, 1);
    }

    // ---------- 跨对象查询 ----------

    @Test
    void queryRevokedGrantIs410AndRegionNotAllowedIs403AndErasedIs410() {
        when(consentRepository.findLatestGrant("subj-a", Purpose.RESEARCH))
                .thenReturn(Optional.of(new GrantRow("subj-a", Purpose.RESEARCH, 1,
                        GrantStatus.REVOKED, "EU", 1)));
        assertThatThrownBy(() -> residencyService.query(new BatchQueryRequest(
                "q-1", "subj-a", Purpose.RESEARCH, List.of("rec-1"), List.of("EU"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(410));

        when(consentRepository.findLatestGrant("subj-a", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "EU", 1)));
        when(consentRepository.findRecords("subj-a", Purpose.RESEARCH, 1, List.of("rec-1")))
                .thenReturn(List.of(record(1, "rec-1", "p1", "US", null)));
        assertThatThrownBy(() -> residencyService.query(new BatchQueryRequest(
                "q-2", "subj-a", Purpose.RESEARCH, List.of("rec-1"), List.of("EU"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(403);
                    assertThat(api.getCode()).isEqualTo("REGION_NOT_ALLOWED");
                });

        when(consentRepository.findRecords("subj-a", Purpose.RESEARCH, 1, List.of("rec-1")))
                .thenReturn(List.of(record(1, "rec-1", null, "EU", T0)));
        assertThatThrownBy(() -> residencyService.query(new BatchQueryRequest(
                "q-3", "subj-a", Purpose.RESEARCH, List.of("rec-1"), List.of("EU"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("RECORD_ERASED"));
        verify(residencyRepository, never()).insertSnapshot(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void querySuccessFreezesSortedItemsAndPersistsSnapshot() {
        when(consentRepository.findLatestGrant("subj-a", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "EU", 3)));
        when(consentRepository.findRecords("subj-a", Purpose.RESEARCH, 1, List.of("rec-2", "rec-1")))
                .thenReturn(List.of(
                        record(1, "rec-2", "p2", "EU", null),
                        record(1, "rec-1", "p1", "EU", null)));

        BatchQueryResponse response = residencyService.query(new BatchQueryRequest(
                "q-1", "subj-a", Purpose.RESEARCH, List.of("rec-2", "rec-1"), List.of("EU")));

        assertThat(response.epoch()).isEqualTo(1);
        assertThat(response.items()).hasSize(2);
        // 按记录键升序固化，授权版本取自代次
        assertThat(response.items().get(0).recordKey()).isEqualTo("rec-1");
        assertThat(response.items().get(0).consentVersion()).isEqualTo(3);
        assertThat(response.createdAt()).isEqualTo(T0);
        verify(residencyRepository).insertSnapshot(
                eq(response.snapshotId()), eq("q-1"), eq("subj-a"), eq(Purpose.RESEARCH), eq(1), eq(T0));
        verify(residencyRepository).insertSnapshotItem(
                response.snapshotId(), "rec-1", 1, "EU", 3, "p1", 0);
        verify(residencyRepository).insertSnapshotItem(
                response.snapshotId(), "rec-2", 1, "EU", 3, "p2", 1);
    }

    @Test
    void getUnknownSnapshotIs404AndReversedIntervalIs400() {
        when(residencyRepository.findSnapshot("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> residencyService.getSnapshot("x"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));

        assertThatThrownBy(() -> residencyService.listSnapshots(
                "subj-a", Purpose.RESEARCH, T0, Instant.parse("2026-09-28T11:00:00Z")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));
    }

    // ---------- 写入路由与清除 ----------

    @Test
    void writeWithMissingOrMismatchedRegionRouteIs403() {
        when(consentRepository.findLatestGrant("subj-a", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "EU", 1)));
        when(consentRepository.findGrant("subj-a", Purpose.RESEARCH, 1))
                .thenReturn(Optional.of(activeGrant(1, "EU", 1)));

        assertThatThrownBy(() -> consentService.write(new RecordWriteRequest(
                "w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REGION_ROUTE_FORBIDDEN"));

        assertThatThrownBy(() -> consentService.write(new RecordWriteRequest(
                "w-2", "subj-a", Purpose.RESEARCH, "rec-1", "p", "US")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo("REGION_ROUTE_FORBIDDEN"));

        verify(consentRepository, never())
                .insertRecord(any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void writeWithUnregisteredRegionOnUnboundEpochIs400() {
        GrantRow unboundGrant = new GrantRow("subj-b", Purpose.RESEARCH, 1, GrantStatus.ACTIVE, null, 1);
        when(consentRepository.findLatestGrant("subj-b", Purpose.RESEARCH))
                .thenReturn(Optional.of(unboundGrant));
        when(consentRepository.findGrant("subj-b", Purpose.RESEARCH, 1))
                .thenReturn(Optional.of(unboundGrant));

        assertThatThrownBy(() -> consentService.write(new RecordWriteRequest(
                "w-3", "subj-b", Purpose.RESEARCH, "rec-1", "p", "APAC")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(400);
                    assertThat(api.getCode()).isEqualTo("REGION_NOT_REGISTERED");
                });
        verify(consentRepository, never())
                .insertRecord(any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void eraseMissingRecordIs404AndSuccessReportsActualCount() {
        when(consentRepository.findRecordsAllEpochs("subj-a", Purpose.RESEARCH, "rec-x"))
                .thenReturn(List.of());
        assertThatThrownBy(() -> consentService.erase(new EraseRequest(
                "e-1", "subj-a", Purpose.RESEARCH, "rec-x")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));

        when(consentRepository.findRecordsAllEpochs("subj-a", Purpose.RESEARCH, "rec-1"))
                .thenReturn(List.of(
                        record(1, "rec-1", "p1", "EU", null),
                        record(2, "rec-1", "p1", "US", null)));
        when(consentRepository.eraseRecords("subj-a", Purpose.RESEARCH, "rec-1", T0)).thenReturn(2);
        assertThat(consentService.erase(new EraseRequest(
                "e-2", "subj-a", Purpose.RESEARCH, "rec-1")).erasedCount()).isEqualTo(2);
    }

    @Test
    void readErasedRecordIs410() {
        when(consentRepository.findLatestGrant("subj-a", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(2, "US", 2)));
        when(consentRepository.findGrant("subj-a", Purpose.RESEARCH, 2))
                .thenReturn(Optional.of(activeGrant(2, "US", 2)));
        when(consentRepository.findRecord("subj-a", Purpose.RESEARCH, 2, "rec-1"))
                .thenReturn(Optional.of(record(2, "rec-1", null, "US", T0)));
        assertThatThrownBy(() -> consentService.read("subj-a", Purpose.RESEARCH, "rec-1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(410);
                    assertThat(api.getCode()).isEqualTo("RECORD_ERASED");
                });
    }

    @Test
    void replayWriteAfterRevokeIsStill410() {
        IdempotencySupport replaying = new IdempotencySupport(idempotencyRepository,
                new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
                return Optional.of(new IdempotencyRow("w-1", "WRITE", "fp", "{}"));
            }

            @Override
            public <T> T readSnapshot(String body, Class<T> type) {
                @SuppressWarnings("unchecked")
                T snapshot = (T) new RecordResponse("subj-a", Purpose.RESEARCH, 1, "rec-1", "p", "EU");
                return snapshot;
            }
        };
        ConsentService service = new ConsentService(consentRepository, residencyRepository, replaying, clock);
        when(consentRepository.findGrant("subj-a", Purpose.RESEARCH, 1))
                .thenReturn(Optional.of(new GrantRow("subj-a", Purpose.RESEARCH, 1,
                        GrantStatus.REVOKED, "EU", 1)));
        assertThatThrownBy(() -> service.write(new RecordWriteRequest(
                "w-1", "subj-a", Purpose.RESEARCH, "rec-1", "p", "EU")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("CONSENT_REVOKED"));
    }
}
