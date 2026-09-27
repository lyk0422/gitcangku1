package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.ConsentRepository.RecordRow;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.LegalHoldRequest;
import com.example.starter.consent.dto.MigrationRequest;
import com.example.starter.consent.dto.QueryRequest;
import com.example.starter.consent.dto.QueryResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 驻留业务规则的纯单元测试：用桩仓储驱动预检/裁决分支与可控 UTC 时钟，
 * 真实 SQL、唯一约束与回滚行为在 {@link ResidencyApiTest} 中用 H2 验证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class ConsentServiceUnitTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Mock
    private ConsentRepository consentRepository;
    @Mock
    private IdempotencyRepository idempotencyRepository;
    @Mock
    private RegionRepository regionRepository;
    @Mock
    private MigrationRepository migrationRepository;
    @Mock
    private ErasureRepository erasureRepository;
    @Mock
    private LegalHoldRepository legalHoldRepository;
    @Mock
    private QuerySnapshotRepository querySnapshotRepository;

    private ConsentService service;

    @BeforeEach
    void setUp() {
        ServiceClock clock = new ServiceClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        service = new ConsentService(consentRepository, idempotencyRepository, regionRepository,
                migrationRepository, erasureRepository, legalHoldRepository, querySnapshotRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()), clock);
        // 并非所有用例都走幂等通道（如合法保留登记），对公共桩放宽严格度
        lenient().when(idempotencyRepository.find(anyString())).thenReturn(Optional.empty());
    }

    private GrantRow activeGrant(int epoch, String region) {
        return new GrantRow("subj", Purpose.RESEARCH, epoch, GrantStatus.ACTIVE, region, null, null);
    }

    private RecordRow record(int epoch, String key, String payload, String region, int version) {
        return new RecordRow("subj", Purpose.RESEARCH, epoch, key, payload, region, version, null, null);
    }

    @Test
    void writeWithWrongRegionIsRejected403BeforeAnyPersistence() {
        when(consentRepository.findLatestGrant("subj", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "CN")));
        when(consentRepository.findGrant("subj", Purpose.RESEARCH, 1))
                .thenReturn(Optional.of(activeGrant(1, "CN")));

        assertThatThrownBy(() -> service.write(new RecordWriteRequest(
                "w-1", "subj", Purpose.RESEARCH, "rec-1", "payload", "EU")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getCode()).isEqualTo(ConsentService.CODE_REGION_MISMATCH);
                });
        verify(consentRepository, never()).insertRecord(
                anyString(), any(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(idempotencyRepository, never())
                .insert(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void grantWithUnregisteredRegionIs404AndActiveEpochRegionChangeIs403() {
        when(consentRepository.lockLatestGrant("subj", Purpose.RESEARCH)).thenReturn(Optional.empty());
        when(regionRepository.find("CN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.grant(new GrantRequest("g-1", "subj", Purpose.RESEARCH, "CN")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                    assertThat(ex.getCode()).isEqualTo(ConsentService.CODE_REGION_NOT_FOUND);
                });

        when(consentRepository.lockLatestGrant("subj", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "CN")));
        // ACTIVE epoch 区域校验先于区域登记校验，此处不需要 EU 登记桩
        assertThatThrownBy(() -> service.grant(new GrantRequest("g-2", "subj", Purpose.RESEARCH, "EU")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getCode()).isEqualTo(ConsentService.CODE_REGION_MISMATCH);
                });
    }

    @Test
    void migrationPrecheckCollectsErasedAndMissingReasonsAndLeavesNoWrites() {
        when(consentRepository.lockLatestGrant("subj", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "CN")));
        when(consentRepository.findGrant("subj", Purpose.RESEARCH, 1))
                .thenReturn(Optional.of(activeGrant(1, "CN")));
        when(regionRepository.find("EU"))
                .thenReturn(Optional.of(new RegionRepository.RegionRow("EU", "欧盟区", NOW)));
        when(consentRepository.findRecord("subj", Purpose.RESEARCH, 1, "rec-ok"))
                .thenReturn(Optional.of(record(1, "rec-ok", "p", "CN", 1)));
        when(consentRepository.findRecord("subj", Purpose.RESEARCH, 1, "rec-erased"))
                .thenReturn(Optional.of(new RecordRow("subj", Purpose.RESEARCH, 1, "rec-erased",
                        null, "CN", 1, NOW, "e-0")));
        when(consentRepository.findRecord("subj", Purpose.RESEARCH, 1, "rec-missing"))
                .thenReturn(Optional.empty());
        when(consentRepository.findRecordAnyEpoch("subj", Purpose.RESEARCH, "rec-missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.migrate(new MigrationRequest(
                "m-1", "subj", Purpose.RESEARCH, 1, "EU",
                List.of("rec-ok", "rec-erased", "rec-missing"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo(ConsentService.CODE_MIGRATION_REJECTED);
                    assertThat(ex.getDetails()).extracting("code")
                            .contains("RECORD_ERASED", "OBJECT_NOT_FOUND");
                    assertThat(ex.getDetails()).extracting("target")
                            .contains("rec-erased", "rec-missing");
                });
        verify(consentRepository, never()).insertGrant(anyString(), any(), anyInt(), anyString(), anyString());
        verify(consentRepository, never()).revokeGrant(anyString(), any(), anyInt(), any(), any());
        verify(consentRepository, never()).copyRecord(any(), anyInt(), anyString(), anyString());
        verify(migrationRepository, never()).insert(any());
    }

    @Test
    void queryIs410WhenEpochRevokedAnd403WhenAnyObjectOutsideAllowedRegions() {
        GrantRow revoked = new GrantRow("subj", Purpose.RESEARCH, 1, GrantStatus.REVOKED, "CN", NOW,
                RevokeReason.USER_REVOKE.name());
        when(consentRepository.lockLatestGrant("subj", Purpose.RESEARCH)).thenReturn(Optional.of(revoked));
        assertThatThrownBy(() -> service.queryRecords(new QueryRequest(
                "q-1", "subj", Purpose.RESEARCH, List.of("CN"), List.of("rec-1"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(410));

        when(consentRepository.lockLatestGrant("subj", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "CN")));
        when(regionRepository.find("EU"))
                .thenReturn(Optional.of(new RegionRepository.RegionRow("EU", "欧盟区", NOW)));
        when(consentRepository.findRecords("subj", Purpose.RESEARCH, 1, List.of("rec-1")))
                .thenReturn(List.of(record(1, "rec-1", "p", "CN", 1)));
        assertThatThrownBy(() -> service.queryRecords(new QueryRequest(
                "q-2", "subj", Purpose.RESEARCH, List.of("EU"), List.of("rec-1"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getCode()).isEqualTo(ConsentService.CODE_REGION_NOT_ALLOWED);
                });
        verify(querySnapshotRepository, never()).insert(any());
    }

    @Test
    void successfulQueryFreezesEpochRegionVersionOnceAndReturnsSortedObjects() {
        when(consentRepository.lockLatestGrant("subj", Purpose.RESEARCH))
                .thenReturn(Optional.of(activeGrant(1, "CN")));
        when(regionRepository.find("CN"))
                .thenReturn(Optional.of(new RegionRepository.RegionRow("CN", "中国区", NOW)));
        when(consentRepository.findRecords(eq("subj"), eq(Purpose.RESEARCH), eq(1), any()))
                .thenReturn(List.of(
                        record(1, "rec-a", "pa", "CN", 2),
                        record(1, "rec-b", "pb", "CN", 3)));

        QueryResponse response = service.queryRecords(new QueryRequest(
                "q-1", "subj", Purpose.RESEARCH, List.of("CN"), List.of("rec-b", "rec-a")));

        assertThat(response.objectCount()).isEqualTo(2);
        // 稳定排序由仓储 SQL ORDER BY record_key ASC 保证（此处桩按该契约返回）
        assertThat(response.objects()).extracting("recordKey")
                .containsExactly("rec-a", "rec-b");
        assertThat(response.objects()).extracting("consentVersion")
                .containsExactly(2, 3);
        verify(querySnapshotRepository, times(1)).insert(any());
        verify(querySnapshotRepository, times(2)).insertItem(any());
    }

    @Test
    void invalidLegalHoldWindowIs400AndZeroLengthWindowCoversNothing() {
        assertThatThrownBy(() -> service.registerLegalHold(new LegalHoldRequest(
                "subj", Purpose.RESEARCH, 1, "rec-1", "CASE", NOW.plusSeconds(10), NOW)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getCode()).isEqualTo(ConsentService.CODE_INVALID_LEGAL_HOLD_WINDOW);
                });

        LegalHoldRepository.LegalHoldRow window = new LegalHoldRepository.LegalHoldRow(
                1, "subj", Purpose.RESEARCH, 1, "rec-1", "CASE", NOW, NOW.plusSeconds(10), NOW);
        assertThat(window.covers(NOW)).isTrue();
        assertThat(window.covers(NOW.plusSeconds(9))).isTrue();
        // 左闭右开：终点不含
        assertThat(window.covers(NOW.plusSeconds(10))).isFalse();
        assertThat(window.covers(NOW.minusSeconds(1))).isFalse();

        LegalHoldRepository.LegalHoldRow zeroLength = new LegalHoldRepository.LegalHoldRow(
                2, "subj", Purpose.RESEARCH, 1, "rec-1", "CASE", NOW, NOW, NOW);
        assertThat(zeroLength.covers(NOW)).isFalse();
    }
}
