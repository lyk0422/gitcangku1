package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.ConsentRepository.RecordRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.EraseRequest;
import com.example.starter.consent.dto.EraseResponse;
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
 * <p>驻留规则：每个授权代次可登记一个驻留区域；代次绑定区域后，写入必须携带相同区域路由，
 * 未携带或携带其他区域返回 403；已清除数据不允许再写入同一记录键。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_REGION_ROUTE_FORBIDDEN = "REGION_ROUTE_FORBIDDEN";
    static final String CODE_REGION_NOT_REGISTERED = "REGION_NOT_REGISTERED";
    static final String CODE_RECORD_ERASED = "RECORD_ERASED";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";

    private final ConsentRepository consentRepository;
    private final ResidencyRepository residencyRepository;
    private final IdempotencySupport idempotency;
    private final Clock clock;

    public ConsentService(ConsentRepository consentRepository,
                          ResidencyRepository residencyRepository,
                          IdempotencySupport idempotency,
                          Clock clock) {
        this.consentRepository = consentRepository;
        this.residencyRepository = residencyRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 授权：当前授权仍有效时返回原代次；撤回后或首次授权生成下一代（从 1 开始递增）。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        String fingerprint = OP_GRANT + "|" + request.subjectKey() + "|" + request.purpose();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotency.readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        Optional<GrantRow> latest =
                consentRepository.findLatestGrant(request.subjectKey(), request.purpose());
        GrantResponse response;
        if (latest.isPresent() && latest.get().status() == GrantStatus.ACTIVE) {
            response = new GrantResponse(request.subjectKey(), request.purpose(),
                    latest.get().epoch(), GrantStatus.ACTIVE);
        } else {
            int nextEpoch = latest.map(GrantRow::epoch).orElse(0) + 1;
            try {
                consentRepository.insertGrant(request.subjectKey(), request.purpose(), nextEpoch, request.requestId());
            } catch (DuplicateKeyException concurrent) {
                // 并发授权同一代次：以已提交的行为准
                GrantRow committed =
                        consentRepository.findGrant(request.subjectKey(), request.purpose(), nextEpoch)
                                .orElseThrow(() -> concurrent);
                response = new GrantResponse(committed.subjectKey(), committed.purpose(),
                        committed.epoch(), committed.status());
                idempotency.storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
                return response;
            }
            response = new GrantResponse(request.subjectKey(), request.purpose(), nextEpoch, GrantStatus.ACTIVE);
        }
        idempotency.storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
        return response;
    }

    /**
     * 写入：仅当前有效代次可写；代次绑定区域后必须携带相同区域路由；
     * 同代同 recordKey 同 payload 去重返回原记录，不同 payload 返回 409；已清除记录键返回 410。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + request.payload()
                + "|" + (request.regionCode() == null ? "" : request.regionCode());
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            RecordResponse snapshot = idempotency.readSnapshot(replayed.get().responseBody(), RecordResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch());
            return snapshot;
        }

        GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());

        // 区域路由：代次已绑定区域时必须携带相同路由，区域本身须预先登记
        String regionCode = resolveWriteRegion(latest, request.regionCode());

        Optional<RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (existing.get().erasedAt() != null) {
                throw ApiException.gone(CODE_RECORD_ERASED, "记录已清除，不能再写入同一记录键");
            }
            if (!existing.get().payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(existing.get());
            idempotency.storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), request.payload(), regionCode, request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (committed.erasedAt() != null) {
                throw ApiException.gone(CODE_RECORD_ERASED, "记录已清除，不能再写入同一记录键");
            }
            if (!committed.payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(committed);
            idempotency.storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        RecordResponse response = new RecordResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), request.recordKey(), request.payload(), regionCode);
        idempotency.storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 撤回：指定代次只允许从有效变为已撤回；撤回提交后旧代查询立即返回 410，写入被拒绝。
     */
    @Transactional
    public GrantResponse revoke(RevokeRequest request) {
        String fingerprint = OP_REVOKE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotency.readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        consentRepository.findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        boolean revoked = consentRepository.revokeGrant(request.subjectKey(), request.purpose(), request.epoch());
        if (!revoked) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回");
        }
        GrantResponse response = new GrantResponse(request.subjectKey(), request.purpose(),
                request.epoch(), GrantStatus.REVOKED);
        idempotency.storeSuccess(request.requestId(), OP_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 查询：仅当前有效代次可查；撤回后返回 410，记录不存在返回 404，已清除返回 410。
     */
    @Transactional(readOnly = true)
    public RecordResponse read(String subjectKey, Purpose purpose, String recordKey) {
        GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        RecordRow record = consentRepository.findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
        if (record.erasedAt() != null) {
            throw ApiException.gone(CODE_RECORD_ERASED, "记录已清除");
        }
        return toResponse(record);
    }

    /**
     * 数据清除：将同一记录键在所有代次中的记录内容置 NULL 并标记清除时间（UTC）。
     * 清除为合规操作，不要求授权有效；不存在记录键返回 404；重复清除幂等返回首次结果。
     */
    @Transactional
    public EraseResponse erase(EraseRequest request) {
        String operation = "ERASE";
        String fingerprint = operation + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotency.readSnapshot(replayed.get().responseBody(), EraseResponse.class);
        }

        List<RecordRow> all = consentRepository.findRecordsAllEpochs(
                request.subjectKey(), request.purpose(), request.recordKey());
        if (all.isEmpty()) {
            throw ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在，无可清除数据");
        }
        Instant erasedAt = clock.instant();
        int erasedCount = consentRepository.eraseRecords(
                request.subjectKey(), request.purpose(), request.recordKey(), erasedAt);
        EraseResponse response = new EraseResponse(request.subjectKey(), request.purpose(),
                request.recordKey(), erasedCount, erasedAt);
        idempotency.storeSuccess(request.requestId(), operation, fingerprint, response);
        return response;
    }

    private void requireGrantActive(String subjectKey, Purpose purpose, int epoch) {
        GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    /**
     * 校验写入区域路由：代次未绑定区域时沿用请求区域（若给出则须预先登记）；
     * 已绑定区域时请求必须显式携带相同区域，否则 403。
     */
    private String resolveWriteRegion(GrantRow grant, String requestedRegion) {
        if (grant.regionCode() == null) {
            if (requestedRegion != null && residencyRepository.findRegion(requestedRegion).isEmpty()) {
                throw ApiException.badRequest(CODE_REGION_NOT_REGISTERED, "驻留区域尚未登记: " + requestedRegion);
            }
            return requestedRegion;
        }
        if (requestedRegion == null || !requestedRegion.equals(grant.regionCode())) {
            throw ApiException.forbidden(CODE_REGION_ROUTE_FORBIDDEN,
                    "写入区域路由与授权代次驻留区域不一致，要求区域: " + grant.regionCode());
        }
        return grant.regionCode();
    }

    private RecordResponse toResponse(RecordRow row) {
        return new RecordResponse(row.subjectKey(), row.purpose(), row.epoch(),
                row.recordKey(), row.payload(), row.regionCode());
    }
}
