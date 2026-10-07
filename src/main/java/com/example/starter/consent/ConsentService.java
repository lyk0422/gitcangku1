package com.example.starter.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;

/**
 * 授权域服务：实现授权、写入、撤回与查询的业务规则及幂等语义。
 *
 * <p>幂等规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原结果，
 * 参数变更返回 409；失败请求不占用 requestId。写入重放不得绕过授权状态：
 * 即使 requestId 命中幂等记录，只要所属代次已撤回，仍返回 410。
 *
 * <p>撤回成功时为该代次建立唯一清除任务（PENDING），并同事务清除该代写入幂等缓存中的
 * 载荷原文；请求指纹中载荷仅以 SHA-256 参与，不残留原文，旧文本不可恢复。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = IdempotencyStore.CODE_REQUEST_ID_CONFLICT;
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";

    private final ConsentRepository consentRepository;
    private final ErasureRepository erasureRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final IdempotencyStore idempotencyStore;

    public ConsentService(ConsentRepository consentRepository,
                          ErasureRepository erasureRepository,
                          IdempotencyRepository idempotencyRepository,
                          IdempotencyStore idempotencyStore) {
        this.consentRepository = consentRepository;
        this.erasureRepository = erasureRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * 授权：当前授权仍有效时返回原代次；撤回后或首次授权生成下一代（从 1 开始递增）。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        String fingerprint = OP_GRANT + "|" + request.subjectKey() + "|" + request.purpose();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotencyStore.readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        Optional<ConsentRepository.GrantRow> latest =
                consentRepository.findLatestGrant(request.subjectKey(), request.purpose());
        GrantResponse response;
        if (latest.isPresent() && latest.get().status() == GrantStatus.ACTIVE) {
            response = new GrantResponse(request.subjectKey(), request.purpose(),
                    latest.get().epoch(), GrantStatus.ACTIVE);
        } else {
            int nextEpoch = latest.map(row -> row.epoch() + 1).orElse(1);
            try {
                consentRepository.insertGrant(request.subjectKey(), request.purpose(), nextEpoch, request.requestId());
            } catch (DuplicateKeyException concurrent) {
                // 并发授权同一代次：以已提交的行为准
                ConsentRepository.GrantRow committed =
                        consentRepository.findGrant(request.subjectKey(), request.purpose(), nextEpoch)
                                .orElseThrow(() -> concurrent);
                response = new GrantResponse(committed.subjectKey(), committed.purpose(),
                        committed.epoch(), committed.status());
                storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
                return response;
            }
            response = new GrantResponse(request.subjectKey(), request.purpose(), nextEpoch, GrantStatus.ACTIVE);
        }
        storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
        return response;
    }

    /**
     * 写入：仅当前有效代次可写；同代同 recordKey 同 payload 去重返回原记录，不同 payload 返回 409。
     * 重放时授权门禁优先：所属代次已撤回返回 410，即使参数指纹不一致也不返回原文。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + latest.epoch() + "|" + request.recordKey() + "|" + sha256(request.payload());

        Optional<IdempotencyRow> existing = idempotencyStore.find(request.requestId());
        if (existing.isPresent()) {
            IdempotencyRow row = existing.get();
            // 授权有效性先于含值重放：已存成功结果所属代次已撤回则立即 410
            if (row.epoch() != null) {
                requireGrantActive(row.subjectKey(), Purpose.valueOf(row.purpose()), row.epoch());
            }
            idempotencyStore.requireSameParams(row, fingerprint);
            return idempotencyStore.readSnapshot(row.responseBody(), RecordResponse.class);
        }

        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());

        Optional<ConsentRepository.RecordRow> existingRecord = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existingRecord.isPresent()) {
            if (!existingRecord.get().payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(existingRecord.get());
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), request.payload(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            ConsentRepository.RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (!committed.payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(committed);
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        RecordResponse response = new RecordResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), request.recordKey(), request.payload());
        storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 撤回：指定代次只允许从有效变为已撤回；撤回提交后旧代查询立即返回 410，写入被拒绝。
     * 同事务建立唯一清除任务（PENDING），并清除该代写入幂等缓存中的载荷原文。
     */
    @Transactional
    public GrantResponse revoke(RevokeRequest request) {
        String fingerprint = OP_REVOKE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotencyStore.readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        consentRepository.findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        boolean revoked = consentRepository.revokeGrant(request.subjectKey(), request.purpose(), request.epoch());
        if (!revoked) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回");
        }
        // 该代次首次进入清除流程：建立唯一清除任务，并清除该代缓存中的载荷原文
        erasureRepository.insertTask(request.subjectKey(), request.purpose(), request.epoch(), request.requestId());
        purgeWriteSnapshots(request.subjectKey(), request.purpose(), request.epoch());

        GrantResponse response = new GrantResponse(request.subjectKey(), request.purpose(),
                request.epoch(), GrantStatus.REVOKED);
        storeSuccess(request.requestId(), OP_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 查询：仅当前有效代次可查；撤回后返回 410，记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public RecordResponse read(String subjectKey, Purpose purpose, String recordKey) {
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        return consentRepository.findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
    }

    private void requireGrantActive(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    /**
     * 幂等重放检查：命中且参数一致返回原快照；参数不一致返回 409。
     */
    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyStore.find(requestId);
        row.ifPresent(existing -> idempotencyStore.requireSameParams(existing, fingerprint));
        return row;
    }

    private void storeSuccess(String requestId, String operation, String fingerprint, Object response) {
        String subjectKey;
        Purpose purpose;
        Integer epoch;
        if (response instanceof GrantResponse grant) {
            subjectKey = grant.subjectKey();
            purpose = grant.purpose();
            epoch = grant.epoch();
        } else if (response instanceof RecordResponse record) {
            subjectKey = record.subjectKey();
            purpose = record.purpose();
            epoch = record.epoch();
        } else {
            throw new IllegalArgumentException("不支持的响应类型: " + response.getClass());
        }
        idempotencyStore.storeSuccess(requestId, operation, fingerprint, response, subjectKey, purpose, epoch);
    }

    /**
     * 撤回后清除该代全部写入幂等缓存中的载荷原文：快照载荷置空，身份与指纹保留。
     */
    private void purgeWriteSnapshots(String subjectKey, Purpose purpose, int epoch) {
        for (IdempotencyRow row : idempotencyRepository.findByScope(OP_WRITE, subjectKey, purpose, epoch)) {
            RecordResponse snapshot = idempotencyStore.readSnapshot(row.responseBody(), RecordResponse.class);
            if (snapshot.payload() != null) {
                RecordResponse erased = new RecordResponse(snapshot.subjectKey(), snapshot.purpose(),
                        snapshot.epoch(), snapshot.recordKey(), null);
                idempotencyRepository.updateResponseBody(row.requestId(), idempotencyStore.writeSnapshot(erased));
            }
        }
    }

    private RecordResponse toResponse(ConsentRepository.RecordRow row) {
        return new RecordResponse(row.subjectKey(), row.purpose(), row.epoch(), row.recordKey(), row.payload());
    }

    /**
     * 载荷原文的 SHA-256（十六进制）：指纹只携带摘要，不残留原文。
     */
    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
