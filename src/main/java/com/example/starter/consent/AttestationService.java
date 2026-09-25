package com.example.starter.consent;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.example.starter.consent.AttestationRepository.AttestationRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.AttestRequest;
import com.example.starter.consent.dto.AttestRevokeRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.RecipientDisableRequest;
import com.example.starter.consent.dto.RecipientStatusResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 接收方证明域服务：证明提交/续签、撤销、接收方整体禁用与证明历史查询。
 *
 * <p>规则：同一接收方＋用途＋代次至多一条生效证明，续签生成新版本而非覆盖旧记录；
 * 到期必须晚于提交时刻；attestKey 指纹含接收方、用途代次、到期与声明摘要，
 * 同键同参重放首次完整响应，失败不占键；撤销仅影响后续查询，不改写已生成快照。
 */
@Service
public class AttestationService {

    static final String CODE_ATTESTATION_NOT_FOUND = "ATTESTATION_NOT_FOUND";
    static final String CODE_ATTESTATION_ALREADY_REVOKED = "ATTESTATION_ALREADY_REVOKED";
    static final String CODE_ATTESTATION_EXPIRES_IN_PAST = "ATTESTATION_EXPIRES_IN_PAST";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    private static final String OP_ATTEST = "ATTEST";
    private static final String OP_ATTEST_REVOKE = "ATTEST_REVOKE";
    private static final String OP_RECIPIENT_DISABLE = "RECIPIENT_DISABLE";

    private static final int MAX_RENEW_ATTEMPTS = 20;

    private final AttestationRepository attestationRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttestationService(AttestationRepository attestationRepository,
                              IdempotencyRepository idempotencyRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.attestationRepository = attestationRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 提交/续签证明：为“接收方＋用途＋代次”生成新版本，旧生效版本转为已取代。
     * 并发续签按事务提交顺序裁决，版本号唯一递增。
     */
    @Transactional
    public AttestationResponse attest(AttestRequest request) {
        String fingerprint = OP_ATTEST + "|" + request.recipientId() + "|" + request.purpose()
                + "|" + request.epoch() + "|" + request.expiresAt() + "|" + request.statementDigest();
        Optional<IdempotencyRow> replayed = checkReplay(request.attestKey(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), AttestationResponse.class);
        }

        if (!request.expiresAt().isAfter(clock.instant())) {
            // 到期必须晚于提交时刻；失败不占用 attestKey
            throw ApiException.badRequest(CODE_ATTESTATION_EXPIRES_IN_PAST, "证明到期时刻必须晚于提交时刻");
        }

        AttestationRow inserted = null;
        for (int attempt = 0; attempt < MAX_RENEW_ATTEMPTS; attempt++) {
            Optional<AttestationRow> active =
                    attestationRepository.findActive(request.recipientId(), request.purpose(), request.epoch());
            int nextVersion = active.map(row -> row.version() + 1).orElse(1);
            active.ifPresent(row -> attestationRepository.supersede(row.id()));
            try {
                attestationRepository.insert(request.recipientId(), request.purpose(), request.epoch(),
                        nextVersion, request.expiresAt(), request.statementDigest(), request.attestKey());
                inserted = new AttestationRow(-1L, request.recipientId(), request.purpose(), request.epoch(),
                        nextVersion, request.expiresAt(), request.statementDigest(), AttestationStatus.ACTIVE);
                break;
            } catch (DuplicateKeyException concurrent) {
                // 并发同 attestKey：先提交方已完成本键，回滚本次业务变更并重放首次完整响应
                Optional<IdempotencyRow> committed = idempotencyRepository.find(request.attestKey());
                if (committed.isPresent()) {
                    if (!committed.get().paramsFingerprint().equals(fingerprint)) {
                        throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一幂等键参数不一致");
                    }
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    return readSnapshot(committed.get().responseBody(), AttestationResponse.class);
                }
                // 并发续签同一版本号：以已提交版本为基线重试
            }
        }
        if (inserted == null) {
            throw new IllegalStateException("证明续签版本冲突重试次数耗尽");
        }

        AttestationResponse response = toResponse(inserted);
        return storeSuccess(request.attestKey(), OP_ATTEST, fingerprint, response, AttestationResponse.class);
    }

    /**
     * 撤销当前生效证明：仅影响后续查询，已生成快照及其证明版本不可改写。
     */
    @Transactional
    public AttestationResponse revoke(AttestRevokeRequest request) {
        String fingerprint = OP_ATTEST_REVOKE + "|" + request.recipientId()
                + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), AttestationResponse.class);
        }

        Optional<AttestationRow> active =
                attestationRepository.findActive(request.recipientId(), request.purpose(), request.epoch());
        if (active.isEmpty()) {
            attestationRepository.findLatest(request.recipientId(), request.purpose(), request.epoch())
                    .orElseThrow(() -> ApiException.notFound(CODE_ATTESTATION_NOT_FOUND, "证明不存在"));
            throw ApiException.conflict(CODE_ATTESTATION_ALREADY_REVOKED, "证明已撤销或已被取代");
        }
        if (!attestationRepository.revoke(active.get().id())) {
            // 并发撤销按事务提交顺序裁决：先提交方已完成本 requestId 时重放首次完整响应
            Optional<IdempotencyRow> committed = idempotencyRepository.find(request.requestId());
            if (committed.isPresent() && committed.get().paramsFingerprint().equals(fingerprint)) {
                return readSnapshot(committed.get().responseBody(), AttestationResponse.class);
            }
            throw ApiException.conflict(CODE_ATTESTATION_ALREADY_REVOKED, "证明已撤销或已被取代");
        }
        AttestationRow revoked = active.get();
        AttestationResponse response = new AttestationResponse(revoked.recipientId(), revoked.purpose(),
                revoked.epoch(), revoked.version(), revoked.expiresAt(), revoked.statementDigest(),
                AttestationStatus.REVOKED);
        return storeSuccess(request.requestId(), OP_ATTEST_REVOKE, fingerprint, response, AttestationResponse.class);
    }

    /**
     * 整体禁用接收方：禁用后所有新批次查询返回 403，即使证明仍有效；重复禁用返回原状态。
     */
    @Transactional
    public RecipientStatusResponse disable(RecipientDisableRequest request) {
        String fingerprint = OP_RECIPIENT_DISABLE + "|" + request.recipientId();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), RecipientStatusResponse.class);
        }

        attestationRepository.disableRecipient(request.recipientId());
        RecipientStatusResponse response = new RecipientStatusResponse(request.recipientId(), "DISABLED");
        return storeSuccess(request.requestId(), OP_RECIPIENT_DISABLE, fingerprint, response,
                RecipientStatusResponse.class);
    }

    /**
     * 查询证明历史：按用途、代次过滤，版本倒序返回全部历史版本（含已取代与已撤销）。
     */
    @Transactional(readOnly = true)
    public List<AttestationResponse> history(String recipientId, Purpose purpose, Integer epoch) {
        return attestationRepository.findHistory(recipientId, purpose, epoch).stream()
                .map(this::toResponse)
                .toList();
    }

    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一幂等键参数不一致");
        }
        return row;
    }

    /**
     * 保存成功结果；并发同键时以先提交事务为准，回滚本次业务变更并重放首次完整响应。
     */
    private <T> T storeSuccess(String requestId, String operation, String fingerprint, T response, Class<T> type) {
        try {
            idempotencyRepository.insert(requestId, operation, fingerprint, writeSnapshot(response));
            return response;
        } catch (DuplicateKeyException concurrent) {
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一幂等键参数不一致");
            }
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return readSnapshot(committed.responseBody(), type);
        }
    }

    private AttestationResponse toResponse(AttestationRow row) {
        return new AttestationResponse(row.recipientId(), row.purpose(), row.epoch(), row.version(),
                row.expiresAt(), row.statementDigest(), row.status());
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
