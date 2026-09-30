package com.example.starter.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.AttestationRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.AttestationRevokeRequest;
import com.example.starter.consent.dto.ReadAuditResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 接收方承诺域服务：接收方按授权 epoch 提交用途与 UTC 到期时刻承诺，
 * 读取时同时匹配接收方、用途、授权 epoch 与有效期，并记录读取依据。
 *
 * <p>读取校验顺序：授权代次 → 接收方承诺（存在性/用途/代次/状态/到期）→ 记录。
 * 任何拒绝都先写读取审计（只含键与原因码，不含业务数据）再拒绝；到期判定以
 * {@code now >= expiresAt} 为准，即到期时刻恰好到达立即失效。读取方法不加事务，
 * 审计写入随每条 SQL 自动提交，保证 403 拒绝抛出后审计仍然保留；撤回提交前读到
 * ACTIVE 承诺即授权成功，撤回提交后只能读到 REVOKED。
 */
@Service
public class AttestationService {

    static final String CODE_ATTESTATION_NOT_FOUND = "ATTESTATION_NOT_FOUND";
    static final String CODE_ATTESTATION_CONFLICT = "ATTESTATION_CONFLICT";
    static final String CODE_ATTESTATION_ALREADY_REVOKED = "ATTESTATION_ALREADY_REVOKED";
    static final String CODE_NO_ATTESTATION = "NO_ATTESTATION";
    static final String CODE_PURPOSE_MISMATCH = "PURPOSE_MISMATCH";
    static final String CODE_ATTESTATION_EXPIRED = "ATTESTATION_EXPIRED";
    static final String CODE_ATTESTATION_REVOKED = "ATTESTATION_REVOKED";
    static final String CODE_GRANT_REVOKED = "GRANT_REVOKED";

    private static final String OP_ATTEST = "ATTEST";
    private static final String OP_ATTEST_REVOKE = "ATTEST_REVOKE";

    private final ConsentRepository consentRepository;
    private final AttestationRepository attestationRepository;
    private final ReadAuditRepository readAuditRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TimeSource timeSource;
    private final ObjectMapper objectMapper;

    public AttestationService(ConsentRepository consentRepository,
                              AttestationRepository attestationRepository,
                              ReadAuditRepository readAuditRepository,
                              IdempotencyRepository idempotencyRepository,
                              TimeSource timeSource,
                              ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.attestationRepository = attestationRepository;
        this.readAuditRepository = readAuditRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.timeSource = timeSource;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收方提交承诺：同一 requestId 重放首次承诺版本；换用途或到期时间返回 409。
     * 承诺针对的授权 epoch 必须存在且仍有效。
     */
    @Transactional
    public AttestationResponse attest(AttestationRequest request) {
        String fingerprint = OP_ATTEST + "|" + request.recipientKey() + "|" + request.subjectKey()
                + "|" + request.purpose() + "|" + request.epoch() + "|" + request.expiresAt();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), AttestationResponse.class);
        }

        ConsentRepository.GrantRow grant = consentRepository
                .findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.conflict(ConsentService.CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回，不能新增承诺");
        }
        if (!request.expiresAt().isAfter(timeSource.now())) {
            throw ApiException.badRequest("ATTESTATION_ALREADY_EXPIRED", "承诺到期时刻必须晚于当前 UTC 时刻");
        }

        Optional<AttestationRepository.AttestationRow> existing = attestationRepository.find(
                request.recipientKey(), request.subjectKey(), request.purpose(), request.epoch());
        AttestationResponse response;
        if (existing.isPresent()) {
            AttestationRepository.AttestationRow row = existing.get();
            if (row.status() == AttestationStatus.REVOKED) {
                // 撤回为终态：不恢复旧 epoch 的承诺，也不允许在同一 epoch 上重新承诺
                throw ApiException.conflict(CODE_ATTESTATION_ALREADY_REVOKED, "接收方承诺已撤回，不能在同一授权代次重新承诺");
            }
            if (!row.expiresAt().equals(request.expiresAt())) {
                throw ApiException.conflict(CODE_ATTESTATION_CONFLICT, "同一授权代次的承诺已存在且到期时刻不同");
            }
            response = toResponse(row);
        } else {
            response = insertAttestation(request);
        }
        storeSuccess(request.requestId(), OP_ATTEST, fingerprint, response);
        return response;
    }

    /**
     * 撤回承诺：终态操作，不删除历史读取记录，也不恢复旧 epoch 的承诺。
     */
    @Transactional
    public AttestationResponse revokeAttestation(AttestationRevokeRequest request) {
        String fingerprint = OP_ATTEST_REVOKE + "|" + request.recipientKey() + "|" + request.subjectKey()
                + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), AttestationResponse.class);
        }

        AttestationRepository.AttestationRow row = attestationRepository.find(
                        request.recipientKey(), request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_ATTESTATION_NOT_FOUND, "接收方承诺不存在"));
        if (row.status() == AttestationStatus.REVOKED
                || !attestationRepository.revoke(request.recipientKey(), request.subjectKey(),
                        request.purpose(), request.epoch(), timeSource.now())) {
            throw ApiException.conflict(CODE_ATTESTATION_ALREADY_REVOKED, "接收方承诺已撤回");
        }
        AttestationResponse response = new AttestationResponse(request.recipientKey(), request.subjectKey(),
                request.purpose(), request.epoch(), row.expiresAt(), AttestationStatus.REVOKED);
        storeSuccess(request.requestId(), OP_ATTEST_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 接收方查询自身全部承诺状态，按主体、用途、代次排序。
     */
    @Transactional(readOnly = true)
    public List<AttestationResponse> listAttestations(String recipientKey) {
        return attestationRepository.findByRecipient(recipientKey).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 接收方读取：同时匹配接收方、用途、授权 epoch 与有效期；成功仅返回授权范围内数据并记录依据，
     * 无承诺、用途不符、已到期或已撤回返回 403 且不返回任何业务数据。
     */
    public RecordResponse read(String recipientKey, String subjectKey, Purpose purpose, String recordKey) {
        Instant now = timeSource.now();

        Optional<ConsentRepository.GrantRow> latest =
                consentRepository.findLatestGrant(subjectKey, purpose);
        Integer currentEpoch = latest.map(ConsentRepository.GrantRow::epoch).orElse(null);

        // 该接收方对该主体的全部承诺，用于区分“无承诺”和“用途不符”
        List<AttestationRepository.AttestationRow> ownAttestations =
                attestationRepository.findByRecipientAndSubject(recipientKey, subjectKey);
        Optional<AttestationRepository.AttestationRow> match = ownAttestations.stream()
                .filter(a -> a.purpose() == purpose)
                .filter(a -> currentEpoch != null && a.epoch() == currentEpoch)
                .findFirst();

        if (match.isEmpty()) {
            String reason;
            boolean samePurposeOtherEpoch = ownAttestations.stream()
                    .anyMatch(a -> a.purpose() == purpose);
            if (samePurposeOtherEpoch) {
                // 承诺用途一致但不是当前授权 epoch（旧代承诺不会恢复，新代尚未承诺）
                reason = CODE_NO_ATTESTATION;
            } else if (ownAttestations.stream().anyMatch(a -> a.purpose() != purpose)) {
                // 只承诺过其他用途：用途不符
                reason = CODE_PURPOSE_MISMATCH;
            } else {
                reason = CODE_NO_ATTESTATION;
            }
            auditDeny(subjectKey, recipientKey, purpose, currentEpoch, recordKey, reason, now);
            throw deny(reason);
        }
        AttestationRepository.AttestationRow attestation = match.get();

        if (attestation.status() == AttestationStatus.REVOKED) {
            auditDeny(subjectKey, recipientKey, purpose, currentEpoch, recordKey,
                    CODE_ATTESTATION_REVOKED, now);
            throw deny(CODE_ATTESTATION_REVOKED);
        }
        if (latest.map(g -> g.status() == GrantStatus.REVOKED).orElse(false)) {
            auditDeny(subjectKey, recipientKey, purpose, currentEpoch, recordKey, CODE_GRANT_REVOKED, now);
            throw deny(CODE_GRANT_REVOKED);
        }
        // 到期时刻恰好到达即失效：now >= expiresAt
        if (!now.isBefore(attestation.expiresAt())) {
            auditDeny(subjectKey, recipientKey, purpose, currentEpoch, recordKey,
                    CODE_ATTESTATION_EXPIRED, now);
            throw deny(CODE_ATTESTATION_EXPIRED);
        }

        ConsentRepository.RecordRow record = consentRepository
                .findRecord(subjectKey, purpose, currentEpoch, recordKey)
                .orElseThrow(() -> {
                    readAuditRepository.insert(subjectKey, recipientKey, purpose, currentEpoch, recordKey,
                            false, ConsentService.CODE_RECORD_NOT_FOUND, null, now);
                    return ApiException.notFound(ConsentService.CODE_RECORD_NOT_FOUND, "记录不存在");
                });

        readAuditRepository.insert(subjectKey, recipientKey, purpose, currentEpoch, recordKey,
                true, null, attestation.expiresAt(), now);
        return new RecordResponse(record.subjectKey(), record.purpose(), record.epoch(),
                record.recordKey(), record.payload());
    }

    /**
     * 授权方查询某主体的读取审计（含成功依据与拒绝原因），按发生顺序返回。
     */
    @Transactional(readOnly = true)
    public List<ReadAuditResponse> listAudits(String subjectKey) {
        return readAuditRepository.findBySubject(subjectKey).stream()
                .map(row -> new ReadAuditResponse(row.id(), row.subjectKey(), row.recipientKey(),
                        row.purpose(), row.epoch(), row.recordKey(), row.result(), row.denyReason(),
                        row.attestationExpiresAt(), row.createdAt()))
                .toList();
    }

    private AttestationResponse insertAttestation(AttestationRequest request) {
        try {
            attestationRepository.insert(request.recipientKey(), request.subjectKey(), request.purpose(),
                    request.epoch(), request.expiresAt(), request.requestId(), timeSource.now());
        } catch (DuplicateKeyException concurrent) {
            // 并发提交同一承诺：以已提交的行为准，到期时刻不同则冲突
            AttestationRepository.AttestationRow committed = attestationRepository.find(
                            request.recipientKey(), request.subjectKey(), request.purpose(), request.epoch())
                    .orElseThrow(() -> concurrent);
            if (!committed.expiresAt().equals(request.expiresAt())) {
                throw ApiException.conflict(CODE_ATTESTATION_CONFLICT, "同一授权代次的承诺已存在且到期时刻不同");
            }
            return toResponse(committed);
        }
        return new AttestationResponse(request.recipientKey(), request.subjectKey(),
                request.purpose(), request.epoch(), request.expiresAt(), AttestationStatus.ACTIVE);
    }

    private void auditDeny(String subjectKey, String recipientKey, Purpose purpose, Integer epoch,
                           String recordKey, String reason, Instant now) {
        readAuditRepository.insert(subjectKey, recipientKey, purpose, epoch, recordKey,
                false, reason, null, now);
    }

    private ApiException deny(String reason) {
        String message = switch (reason) {
            case CODE_NO_ATTESTATION -> "接收方未对当前授权代次提交有效承诺";
            case CODE_PURPOSE_MISMATCH -> "承诺用途与读取用途不一致";
            case CODE_ATTESTATION_EXPIRED -> "接收方承诺已到期";
            case CODE_ATTESTATION_REVOKED -> "接收方承诺已撤回";
            case CODE_GRANT_REVOKED -> "授权已撤回";
            default -> "读取被拒绝";
        };
        return ApiException.forbidden(reason, message);
    }

    private AttestationResponse toResponse(AttestationRepository.AttestationRow row) {
        return new AttestationResponse(row.recipientKey(), row.subjectKey(), row.purpose(),
                row.epoch(), row.expiresAt(), row.status());
    }

    /**
     * 幂等重放检查：命中且参数一致返回原快照；参数不一致返回 409。
     */
    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(ConsentService.CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
        return row;
    }

    private void storeSuccess(String requestId, String operation, String fingerprint, Object response) {
        try {
            idempotencyRepository.insert(requestId, operation, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            // 并发同 requestId：校验已提交快照参数一致，否则视为冲突
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(ConsentService.CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
    }

    private String writeSnapshot(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照序列化失败", e);
        }
    }

    private <T> T readSnapshot(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照反序列化失败", e);
        }
    }
}
