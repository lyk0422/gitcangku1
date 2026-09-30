package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.AttestationRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.AttestationRevokeRequest;
import com.example.starter.consent.dto.RecordResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 接收方承诺服务单元测试：用可控时钟验证到期边界，用 mock 仓储覆盖各校验分支。
 */
@ExtendWith(MockitoExtension.class)
class AttestationServiceTest {

    private static final String RECIPIENT = "recipient-R";
    private static final String SUBJECT = "subject-1";
    private static final String RECORD_KEY = "rec-1";
    private static final Instant T1159 = Instant.parse("2026-09-30T11:59:00Z");
    private static final Instant T1200 = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant T1300 = Instant.parse("2026-09-30T13:00:00Z");

    @Mock
    private ConsentRepository consentRepository;
    @Mock
    private AttestationRepository attestationRepository;
    @Mock
    private ReadAuditRepository readAuditRepository;
    @Mock
    private IdempotencyRepository idempotencyRepository;

    private MutableTimeSource clock;
    private AttestationService service;

    @BeforeEach
    void setUp() {
        clock = new MutableTimeSource(T1159);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new AttestationService(consentRepository, attestationRepository, readAuditRepository,
                idempotencyRepository, clock, objectMapper);
    }

    private AttestationRequest attestRequest(String requestId, Purpose purpose, int epoch, Instant expiresAt) {
        return new AttestationRequest(requestId, RECIPIENT, SUBJECT, purpose, epoch, expiresAt);
    }

    private ConsentRepository.GrantRow activeGrant(Purpose purpose, int epoch) {
        return new ConsentRepository.GrantRow(SUBJECT, purpose, epoch, GrantStatus.ACTIVE);
    }

    private AttestationRepository.AttestationRow attestation(Purpose purpose, int epoch,
                                                             Instant expiresAt, AttestationStatus status) {
        return new AttestationRepository.AttestationRow(
                RECIPIENT, SUBJECT, purpose, epoch, expiresAt, status);
    }

    // ---------- 承诺提交 ----------

    @Test
    void attestCreatesWhenGrantActiveAndNoExisting() {
        when(idempotencyRepository.find("a-1")).thenReturn(Optional.empty());
        when(consentRepository.findGrant(SUBJECT, Purpose.AUDIT, 4)).thenReturn(Optional.of(activeGrant(Purpose.AUDIT, 4)));
        when(attestationRepository.find(RECIPIENT, SUBJECT, Purpose.AUDIT, 4)).thenReturn(Optional.empty());

        AttestationResponse response = service.attest(attestRequest("a-1", Purpose.AUDIT, 4, T1300));

        assertThat(response.status()).isEqualTo(AttestationStatus.ACTIVE);
        assertThat(response.epoch()).isEqualTo(4);
        assertThat(response.expiresAt()).isEqualTo(T1300);
        verify(attestationRepository).insert(eq(RECIPIENT), eq(SUBJECT), eq(Purpose.AUDIT), eq(4),
                eq(T1300), eq("a-1"), eq(T1159));
        verify(idempotencyRepository).insert(eq("a-1"), eq("ATTEST"), any(), any());
    }

    @Test
    void attestReplaySameRequestIdReturnsFirstVersion() {
        String fingerprint = "ATTEST|" + RECIPIENT + "|" + SUBJECT + "|AUDIT|4|" + T1300;
        String snapshot = "{\"recipientKey\":\"recipient-R\",\"subjectKey\":\"subject-1\","
                + "\"purpose\":\"AUDIT\",\"epoch\":4,\"expiresAt\":\"2026-09-30T13:00:00Z\",\"status\":\"ACTIVE\"}";
        when(idempotencyRepository.find("a-1"))
                .thenReturn(Optional.of(new IdempotencyRow("a-1", "ATTEST", fingerprint, snapshot)));

        AttestationResponse response = service.attest(attestRequest("a-1", Purpose.AUDIT, 4, T1300));

        assertThat(response.epoch()).isEqualTo(4);
        assertThat(response.expiresAt()).isEqualTo(T1300);
        verify(attestationRepository, never()).insert(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    @Test
    void attestSameRequestIdWithDifferentExpiryReturns409() {
        when(idempotencyRepository.find("a-1"))
                .thenReturn(Optional.of(new IdempotencyRow("a-1", "ATTEST", "different-fingerprint", "{}")));

        assertThatThrownBy(() -> service.attest(attestRequest("a-1", Purpose.AUDIT, 4, T1300)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ConsentService.CODE_REQUEST_ID_CONFLICT));
    }

    @Test
    void attestAgainstMissingGrantReturns404() {
        when(idempotencyRepository.find("a-1")).thenReturn(Optional.empty());
        when(consentRepository.findGrant(SUBJECT, Purpose.AUDIT, 9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.attest(attestRequest("a-1", Purpose.AUDIT, 9, T1300)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ConsentService.CODE_GRANT_NOT_FOUND));
        verify(attestationRepository, never()).insert(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    @Test
    void attestAgainstRevokedGrantRejected() {
        when(idempotencyRepository.find("a-1")).thenReturn(Optional.empty());
        when(consentRepository.findGrant(SUBJECT, Purpose.AUDIT, 3))
                .thenReturn(Optional.of(new ConsentRepository.GrantRow(SUBJECT, Purpose.AUDIT, 3, GrantStatus.REVOKED)));

        assertThatThrownBy(() -> service.attest(attestRequest("a-1", Purpose.AUDIT, 3, T1300)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ConsentService.CODE_GRANT_ALREADY_REVOKED));
    }

    @Test
    void attestWithPastExpiryReturns400() {
        when(idempotencyRepository.find("a-1")).thenReturn(Optional.empty());
        when(consentRepository.findGrant(SUBJECT, Purpose.AUDIT, 4)).thenReturn(Optional.of(activeGrant(Purpose.AUDIT, 4)));

        assertThatThrownBy(() -> service.attest(attestRequest("a-1", Purpose.AUDIT, 4, T1159)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("ATTESTATION_ALREADY_EXPIRED"));
    }

    @Test
    void attestExistingSameExpiryIsIdempotentWithoutInsert() {
        when(idempotencyRepository.find("a-2")).thenReturn(Optional.empty());
        when(consentRepository.findGrant(SUBJECT, Purpose.AUDIT, 4)).thenReturn(Optional.of(activeGrant(Purpose.AUDIT, 4)));
        when(attestationRepository.find(RECIPIENT, SUBJECT, Purpose.AUDIT, 4))
                .thenReturn(Optional.of(attestation(Purpose.AUDIT, 4, T1300, AttestationStatus.ACTIVE)));

        AttestationResponse response = service.attest(attestRequest("a-2", Purpose.AUDIT, 4, T1300));

        assertThat(response.status()).isEqualTo(AttestationStatus.ACTIVE);
        verify(attestationRepository, never()).insert(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    @Test
    void attestExistingDifferentExpiryReturns409() {
        when(idempotencyRepository.find("a-2")).thenReturn(Optional.empty());
        when(consentRepository.findGrant(SUBJECT, Purpose.AUDIT, 4)).thenReturn(Optional.of(activeGrant(Purpose.AUDIT, 4)));
        when(attestationRepository.find(RECIPIENT, SUBJECT, Purpose.AUDIT, 4))
                .thenReturn(Optional.of(attestation(Purpose.AUDIT, 4, T1300, AttestationStatus.ACTIVE)));

        assertThatThrownBy(() -> service.attest(attestRequest("a-2", Purpose.AUDIT, 4,
                Instant.parse("2026-09-30T15:00:00Z"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(AttestationService.CODE_ATTESTATION_CONFLICT));
    }

    @Test
    void attestOnRevokedAttestationCannotRecommit() {
        when(idempotencyRepository.find("a-3")).thenReturn(Optional.empty());
        when(consentRepository.findGrant(SUBJECT, Purpose.AUDIT, 4)).thenReturn(Optional.of(activeGrant(Purpose.AUDIT, 4)));
        when(attestationRepository.find(RECIPIENT, SUBJECT, Purpose.AUDIT, 4))
                .thenReturn(Optional.of(attestation(Purpose.AUDIT, 4, T1300, AttestationStatus.REVOKED)));

        assertThatThrownBy(() -> service.attest(attestRequest("a-3", Purpose.AUDIT, 4, T1300)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(AttestationService.CODE_ATTESTATION_ALREADY_REVOKED));
    }

    // ---------- 承诺撤回 ----------

    @Test
    void revokeAttestationSucceedsWhenActive() {
        when(idempotencyRepository.find("rv-1")).thenReturn(Optional.empty());
        when(attestationRepository.find(RECIPIENT, SUBJECT, Purpose.AUDIT, 4))
                .thenReturn(Optional.of(attestation(Purpose.AUDIT, 4, T1300, AttestationStatus.ACTIVE)));
        when(attestationRepository.revoke(RECIPIENT, SUBJECT, Purpose.AUDIT, 4, T1159)).thenReturn(true);

        AttestationResponse response = service.revokeAttestation(
                new AttestationRevokeRequest("rv-1", RECIPIENT, SUBJECT, Purpose.AUDIT, 4));

        assertThat(response.status()).isEqualTo(AttestationStatus.REVOKED);
        verify(idempotencyRepository).insert(eq("rv-1"), eq("ATTEST_REVOKE"), any(), any());
    }

    @Test
    void revokeAttestationReplayReturnsOriginal() {
        String fingerprint = "ATTEST_REVOKE|" + RECIPIENT + "|" + SUBJECT + "|AUDIT|4";
        String snapshot = "{\"recipientKey\":\"recipient-R\",\"subjectKey\":\"subject-1\","
                + "\"purpose\":\"AUDIT\",\"epoch\":4,\"expiresAt\":\"2026-09-30T13:00:00Z\",\"status\":\"REVOKED\"}";
        when(idempotencyRepository.find("rv-1"))
                .thenReturn(Optional.of(new IdempotencyRow("rv-1", "ATTEST_REVOKE", fingerprint, snapshot)));

        AttestationResponse response = service.revokeAttestation(
                new AttestationRevokeRequest("rv-1", RECIPIENT, SUBJECT, Purpose.AUDIT, 4));

        assertThat(response.status()).isEqualTo(AttestationStatus.REVOKED);
        verify(attestationRepository, never()).revoke(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void revokeMissingAttestationReturns404() {
        when(idempotencyRepository.find("rv-2")).thenReturn(Optional.empty());
        when(attestationRepository.find(RECIPIENT, SUBJECT, Purpose.AUDIT, 4)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeAttestation(
                new AttestationRevokeRequest("rv-2", RECIPIENT, SUBJECT, Purpose.AUDIT, 4)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(AttestationService.CODE_ATTESTATION_NOT_FOUND));
    }

    @Test
    void revokeAlreadyRevokedReturns409() {
        when(idempotencyRepository.find("rv-3")).thenReturn(Optional.empty());
        when(attestationRepository.find(RECIPIENT, SUBJECT, Purpose.AUDIT, 4))
                .thenReturn(Optional.of(attestation(Purpose.AUDIT, 4, T1300, AttestationStatus.REVOKED)));

        assertThatThrownBy(() -> service.revokeAttestation(
                new AttestationRevokeRequest("rv-3", RECIPIENT, SUBJECT, Purpose.AUDIT, 4)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(AttestationService.CODE_ATTESTATION_ALREADY_REVOKED));
    }

    // ---------- 读取校验 ----------

    private void stubActiveSetup(Purpose readPurpose, List<AttestationRepository.AttestationRow> own) {
        when(consentRepository.findLatestGrant(SUBJECT, readPurpose))
                .thenReturn(Optional.of(activeGrant(readPurpose, 4)));
        when(attestationRepository.findByRecipientAndSubject(RECIPIENT, SUBJECT)).thenReturn(own);
    }

    @Test
    void readSuccessReturnsDataAndRecordsAuditBasis() {
        stubActiveSetup(Purpose.AUDIT, List.of(attestation(Purpose.AUDIT, 4, T1200, AttestationStatus.ACTIVE)));
        when(consentRepository.findRecord(SUBJECT, Purpose.AUDIT, 4, RECORD_KEY))
                .thenReturn(Optional.of(new ConsentRepository.RecordRow(
                        SUBJECT, Purpose.AUDIT, 4, RECORD_KEY, "audit-payload")));

        RecordResponse response = service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY);

        assertThat(response.payload()).isEqualTo("audit-payload");
        assertThat(response.epoch()).isEqualTo(4);
        verify(readAuditRepository).insert(SUBJECT, RECIPIENT, Purpose.AUDIT, 4, RECORD_KEY,
                true, null, T1200, T1159);
    }

    @Test
    void readOneMinuteBeforeExpirySucceeds() {
        clock.setTime(Instant.parse("2026-09-30T11:59:00Z"));
        stubActiveSetup(Purpose.AUDIT, List.of(attestation(Purpose.AUDIT, 4, T1200, AttestationStatus.ACTIVE)));
        when(consentRepository.findRecord(SUBJECT, Purpose.AUDIT, 4, RECORD_KEY))
                .thenReturn(Optional.of(new ConsentRepository.RecordRow(
                        SUBJECT, Purpose.AUDIT, 4, RECORD_KEY, "audit-payload")));

        assertThat(service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY).payload()).isEqualTo("audit-payload");
    }

    @Test
    void readExactlyAtExpiryIsDenied() {
        clock.setTime(T1200);
        stubActiveSetup(Purpose.AUDIT, List.of(attestation(Purpose.AUDIT, 4, T1200, AttestationStatus.ACTIVE)));

        assertThatThrownBy(() -> service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(403);
                    assertThat(api.getCode()).isEqualTo(AttestationService.CODE_ATTESTATION_EXPIRED);
                });
        // 拒绝不含业务数据：审计只记录键与原因，且不查询记录内容
        verify(readAuditRepository).insert(eq(SUBJECT), eq(RECIPIENT), eq(Purpose.AUDIT), eq(4),
                eq(RECORD_KEY), eq(false), eq(AttestationService.CODE_ATTESTATION_EXPIRED), eq(null), eq(T1200));
        verify(consentRepository, never()).findRecord(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void readWithoutAnyGrantIsDeniedNoAttestation() {
        when(consentRepository.findLatestGrant(SUBJECT, Purpose.AUDIT)).thenReturn(Optional.empty());
        when(attestationRepository.findByRecipientAndSubject(RECIPIENT, SUBJECT)).thenReturn(List.of());

        assertThatThrownBy(() -> service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(AttestationService.CODE_NO_ATTESTATION));
        verify(readAuditRepository).insert(eq(SUBJECT), eq(RECIPIENT), eq(Purpose.AUDIT),
                eq(null), eq(RECORD_KEY), eq(false), eq(AttestationService.CODE_NO_ATTESTATION),
                eq(null), eq(T1159));
    }

    @Test
    void readWithoutAttestationIsDenied() {
        stubActiveSetup(Purpose.AUDIT, List.of());

        assertThatThrownBy(() -> service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(403);
                    assertThat(api.getCode()).isEqualTo(AttestationService.CODE_NO_ATTESTATION);
                });
        verify(readAuditRepository).insert(eq(SUBJECT), eq(RECIPIENT), eq(Purpose.AUDIT), eq(4),
                eq(RECORD_KEY), eq(false), eq(AttestationService.CODE_NO_ATTESTATION), eq(null), eq(T1159));
    }

    @Test
    void readWithWrongPurposeIsDeniedAsPurposeMismatch() {
        // 接收方只承诺了 epoch 4 的 MARKETING，却按 AUDIT 读取
        stubActiveSetup(Purpose.AUDIT,
                List.of(attestation(Purpose.MARKETING, 4, T1300, AttestationStatus.ACTIVE)));

        assertThatThrownBy(() -> service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(AttestationService.CODE_PURPOSE_MISMATCH));
    }

    @Test
    void readWithRevokedAttestationIsDenied() {
        stubActiveSetup(Purpose.AUDIT, List.of(attestation(Purpose.AUDIT, 4, T1300, AttestationStatus.REVOKED)));

        assertThatThrownBy(() -> service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(AttestationService.CODE_ATTESTATION_REVOKED));
        verify(readAuditRepository).insert(eq(SUBJECT), eq(RECIPIENT), eq(Purpose.AUDIT), eq(4),
                eq(RECORD_KEY), eq(false), eq(AttestationService.CODE_ATTESTATION_REVOKED),
                eq(null), eq(T1159));
    }

    @Test
    void readWhenGrantRevokedIsDeniedEvenIfAttestationActive() {
        when(consentRepository.findLatestGrant(SUBJECT, Purpose.AUDIT))
                .thenReturn(Optional.of(new ConsentRepository.GrantRow(SUBJECT, Purpose.AUDIT, 4, GrantStatus.REVOKED)));
        when(attestationRepository.findByRecipientAndSubject(RECIPIENT, SUBJECT))
                .thenReturn(List.of(attestation(Purpose.AUDIT, 4, T1300, AttestationStatus.ACTIVE)));

        assertThatThrownBy(() -> service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(AttestationService.CODE_GRANT_REVOKED));
    }

    @Test
    void readMissingRecordReturns404AndAuditsDenialWithoutLeakingData() {
        stubActiveSetup(Purpose.AUDIT, List.of(attestation(Purpose.AUDIT, 4, T1200, AttestationStatus.ACTIVE)));
        when(consentRepository.findRecord(SUBJECT, Purpose.AUDIT, 4, RECORD_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(ConsentService.CODE_RECORD_NOT_FOUND));
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(readAuditRepository).insert(eq(SUBJECT), eq(RECIPIENT), eq(Purpose.AUDIT), eq(4),
                eq(RECORD_KEY), eq(false), reasonCaptor.capture(), eq(null), eq(T1159));
        assertThat(reasonCaptor.getValue()).isEqualTo(ConsentService.CODE_RECORD_NOT_FOUND);
    }

    @Test
    void readOnlyMatchesCurrentEpochAttestation() {
        // 只持有旧 epoch 3 的承诺，当前已到 epoch 4：按无当前承诺拒绝，不返回旧代数据
        stubActiveSetup(Purpose.AUDIT, List.of(attestation(Purpose.AUDIT, 3, T1300, AttestationStatus.ACTIVE)));

        assertThatThrownBy(() -> service.read(RECIPIENT, SUBJECT, Purpose.AUDIT, RECORD_KEY))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode())
                        .isEqualTo(AttestationService.CODE_NO_ATTESTATION));
        verify(consentRepository, never()).findRecord(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }
}
