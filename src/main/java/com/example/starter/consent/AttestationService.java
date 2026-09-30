package com.example.starter.consent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.AttestationRepository.AttestationRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.ReadAuditRepository.ReadAuditRow;
import com.example.starter.consent.dto.AttestationRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.AttestationStatusView;
import com.example.starter.consent.dto.ReadAuditView;
import com.example.starter.consent.dto.RecipientReadResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 接收方承诺域服务。
 *
 * <p>读取授权必须同时匹配接收方、用途、授权 epoch 与有效期四要素：无承诺、用途不符、
 * 已到期（到期时刻恰好到达即失效）或授权已撤回时返回 403，且不返回任何业务数据。
 *
 * <p>并发语义：读取事务先以 {@code SELECT ... FOR UPDATE} 锁定对应授权代次行再判定状态，
 * 撤回事务更新同一行，二者在该行上串行化——只允许在撤回提交前完成授权成功的读取，
 * 撤回提交后的读取只能看到 REVOKED 状态。
 *
 * <p>幂等语义：同一 requestId 重放首次承诺版本；换用途或到期时刻返回 409；
 * 失败请求不占用 requestId。
 */
@Service
public class AttestationService {

    static final String CODE_ATTESTATION_NOT_FOUND = "ATTESTATION_NOT_FOUND";
    static final String CODE_ATTESTATION_PURPOSE_MISMATCH = "ATTESTATION_PURPOSE_MISMATCH";
    static final String CODE_ATTESTATION_EXPIRED = "ATTESTATION_EXPIRED";
    static final String CODE_ATTESTATION_CONFLICT = "ATTESTATION_CONFLICT";
    static final String CODE_ATTESTATION_GRANT_REVOKED = "ATTESTATION_GRANT_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    private static final String OP_ATTEST = "ATTEST";

    private final AttestationRepository attestationRepository;
    private final ReadAuditRepository readAuditRepository;
    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TimeSource timeSource;
    private final ObjectMapper objectMapper;

    public AttestationService(AttestationRepository attestationRepository,
                              ReadAuditRepository readAuditRepository,
                              ConsentRepository consentRepository,
                              IdempotencyRepository idempotencyRepository,
                              TimeSource timeSource,
                              ObjectMapper objectMapper) {
        this.attestationRepository = attestationRepository;
        this.readAuditRepository = readAuditRepository;
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.timeSource = timeSource;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收方提交承诺：就指定授权代次声明用途与 UTC 到期时刻。
     * 同一 requestId 相同参数重放返回首次承诺；参数变更返回 409。
     */
    @Transactional
    public AttestationResponse attest(AttestationRequest request) {
        // 到期时刻规范化为毫秒，与 BIGINT 存储精度一致，避免各数据库小数秒舍入差异
        Instant expiresAt = request.expiresAt().truncatedTo(ChronoUnit.MILLIS);
        String fingerprint = OP_ATTEST + "|" + request.recipientKey() + "|" + request.subjectKey()
                + "|" + request.purpose() + "|" + request.epoch() + "|" + expiresAt;
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), AttestationResponse.class);
        }

        ConsentRepository.GrantRow grant = consentRepository
                .findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.conflict(CODE_ATTESTATION_GRANT_REVOKED, "授权代次已撤回，不能再提交承诺");
        }

        Optional<AttestationRow> existing = attestationRepository.find(
                request.recipientKey(), request.subjectKey(), request.purpose(), request.epoch());
        if (existing.isPresent()) {
            AttestationRow row = existing.get();
            // 同一接收方/用途/代次只允许一个到期时刻；换到期时刻返回 409，完全相同则幂等返回原承诺
            if (!row.expiresAt().equals(expiresAt)) {
                throw ApiException.conflict(CODE_ATTESTATION_CONFLICT, "该授权代次已存在不同到期时刻的承诺");
            }
            AttestationResponse response = toResponse(row, GrantStatus.ACTIVE);
            storeSuccess(request.requestId(), fingerprint, response);
            return response;
        }

        Instant now = timeSource.now();
        AttestationRow row = new AttestationRow(request.recipientKey(), request.subjectKey(),
                request.purpose(), request.epoch(), expiresAt, now, request.requestId());
        try {
            attestationRepository.insert(row);
        } catch (DuplicateKeyException concurrent) {
            // 并发提交同一承诺槽位：以已提交的承诺为准
            AttestationRow committed = attestationRepository.find(
                            request.recipientKey(), request.subjectKey(), request.purpose(), request.epoch())
                    .orElseThrow(() -> concurrent);
            if (!committed.expiresAt().equals(request.expiresAt())) {
                throw ApiException.conflict(CODE_ATTESTATION_CONFLICT, "该授权代次已存在不同到期时刻的承诺");
            }
            AttestationResponse response = toResponse(committed, GrantStatus.ACTIVE);
            storeSuccess(request.requestId(), fingerprint, response);
            return response;
        }

        AttestationResponse response = toResponse(row, GrantStatus.ACTIVE);
        storeSuccess(request.requestId(), fingerprint, response);
        return response;
    }

    /**
     * 接收方读取：四要素全部匹配且授权未撤回时返回授权范围内的记录并记录读取依据；
     * 任一条件不满足返回 403，不返回业务数据。
     */
    @Transactional
    public RecipientReadResponse read(String recipientKey, String subjectKey, Purpose purpose,
                                      int epoch, String recordKey) {
        // 先锁授权代次行，与撤回事务串行：撤回提交后的读取必然看到 REVOKED
        Optional<ConsentRepository.GrantRow> grantOpt =
                consentRepository.findGrantForUpdate(subjectKey, purpose, epoch);
        if (grantOpt.isEmpty()) {
            // 请求用途的授权不存在：若接收方就该主体/代次承诺过其他用途，判定为用途不符而非无承诺
            throw purposeMismatchIfPromisedOtherwise(recipientKey, subjectKey, epoch, purpose)
                    .orElseGet(() -> ApiException.forbidden(CODE_ATTESTATION_NOT_FOUND, "无有效承诺，禁止读取"));
        }
        ConsentRepository.GrantRow grant = grantOpt.get();
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.forbidden(CODE_CONSENT_REVOKED, "授权已撤回，禁止读取");
        }

        Optional<AttestationRow> samePurpose = attestationRepository.findForUpdate(
                recipientKey, subjectKey, purpose, epoch);
        if (samePurpose.isEmpty()) {
            throw purposeMismatchIfPromisedOtherwise(recipientKey, subjectKey, epoch, purpose)
                    .orElseGet(() -> ApiException.forbidden(CODE_ATTESTATION_NOT_FOUND, "无有效承诺，禁止读取"));
        }

        Instant now = timeSource.now();
        // 到期时刻恰好到达即失效：now >= expiresAt
        if (!now.isBefore(samePurpose.get().expiresAt())) {
            throw ApiException.forbidden(CODE_ATTESTATION_EXPIRED, "承诺已到期，禁止读取");
        }

        ConsentRepository.RecordRow record = consentRepository
                .findRecord(subjectKey, purpose, epoch, recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));

        long auditId = readAuditRepository.insert(subjectKey, recipientKey, purpose, epoch, recordKey, now);
        return new RecipientReadResponse(subjectKey, recipientKey, purpose, epoch,
                record.recordKey(), record.payload(), auditId, now);
    }

    /**
     * 若接收方就该主体/代次承诺过与请求用途不同的承诺，返回“用途不符”403；否则返回 empty。
     * 两种失败都不返回业务数据。
     */
    private Optional<ApiException> purposeMismatchIfPromisedOtherwise(
            String recipientKey, String subjectKey, int epoch, Purpose purpose) {
        boolean promisedOtherPurpose = attestationRepository
                .findByRecipientAndEpoch(recipientKey, subjectKey, epoch).stream()
                .anyMatch(row -> row.purpose() != purpose);
        return promisedOtherPurpose
                ? Optional.of(ApiException.forbidden(CODE_ATTESTATION_PURPOSE_MISMATCH, "承诺用途与读取用途不符，禁止读取"))
                : Optional.empty();
    }

    /**
     * 接收方查询自身全部承诺状态（ACTIVE / EXPIRED / REVOKED）。
     */
    @Transactional(readOnly = true)
    public List<AttestationStatusView> listStatuses(String recipientKey) {
        Instant now = timeSource.now();
        return attestationRepository.findByRecipient(recipientKey).stream()
                .map(row -> new AttestationStatusView(row.subjectKey(), row.purpose(), row.epoch(),
                        row.expiresAt(), resolveStatus(row, now), row.createdAt()))
                .toList();
    }

    /**
     * 授权方查询某主体下的读取审计；撤回不删除历史读取记录。
     */
    @Transactional(readOnly = true)
    public List<ReadAuditView> listAudits(String subjectKey) {
        return readAuditRepository.findBySubject(subjectKey).stream()
                .map(this::toView)
                .toList();
    }

    private AttestationStatus resolveStatus(AttestationRow row, Instant now) {
        boolean revoked = consentRepository
                .findGrant(row.subjectKey(), row.purpose(), row.epoch())
                .filter(grant -> grant.status() == GrantStatus.REVOKED)
                .isPresent();
        if (revoked) {
            return AttestationStatus.REVOKED;
        }
        return now.isBefore(row.expiresAt()) ? AttestationStatus.ACTIVE : AttestationStatus.EXPIRED;
    }

    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
        return row;
    }

    private void storeSuccess(String requestId, String fingerprint, Object response) {
        try {
            idempotencyRepository.insert(requestId, OP_ATTEST, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
    }

    private AttestationResponse toResponse(AttestationRow row, GrantStatus grantStatus) {
        AttestationStatus status = grantStatus == GrantStatus.REVOKED
                ? AttestationStatus.REVOKED
                : timeSource.now().isBefore(row.expiresAt()) ? AttestationStatus.ACTIVE : AttestationStatus.EXPIRED;
        return new AttestationResponse(row.recipientKey(), row.subjectKey(), row.purpose(), row.epoch(),
                row.expiresAt(), status, row.createdAt());
    }

    private ReadAuditView toView(ReadAuditRow row) {
        return new ReadAuditView(row.id(), row.subjectKey(), row.recipientKey(), row.purpose(),
                row.epoch(), row.recordKey(), row.readAt());
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
