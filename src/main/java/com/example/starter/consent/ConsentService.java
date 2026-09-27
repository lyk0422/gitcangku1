package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.EraseRequest;
import com.example.starter.consent.dto.EraseResponse;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权域服务：实现授权、写入、撤回、清除与查询的业务规则及幂等语义。
 *
 * <p>幂等规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原结果，
 * 参数变更返回 409；失败请求不占用 requestId。写入重放不得绕过授权状态：
 * 即使 requestId 命中幂等记录，只要所属代次已撤回，仍返回 410。
 *
 * <p>驻留路由规则：授权代次可登记一个预先登记的驻留区域，登记后不可修改；
 * 数据写入必须携带与所属代次一致的区域路由，否则 403。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_REGION_NOT_REGISTERED = "REGION_NOT_REGISTERED";
    static final String CODE_GRANT_REGION_IMMUTABLE = "GRANT_REGION_IMMUTABLE";
    static final String CODE_REGION_ROUTE_MISMATCH = "REGION_ROUTE_MISMATCH";
    static final String CODE_DATA_ERASED = "DATA_ERASED";
    static final String CODE_DATA_ALREADY_ERASED = "DATA_ALREADY_ERASED";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";
    private static final String OP_ERASE = "ERASE";

    private final ConsentRepository consentRepository;
    private final ResidencyRepository residencyRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ConsentService(ConsentRepository consentRepository,
                          ResidencyRepository residencyRepository,
                          IdempotencyRepository idempotencyRepository,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.consentRepository = consentRepository;
        this.residencyRepository = residencyRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 授权：当前授权仍有效时返回原代次；撤回后或首次授权生成下一代（从 1 开始递增）。
     * 新代次可登记一个预先登记的驻留区域；当前授权仍有效时不得变更区域。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        String fingerprint = OP_GRANT + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + Objects.toString(request.regionCode(), "");
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        if (request.regionCode() != null && !residencyRepository.regionExists(request.regionCode())) {
            throw ApiException.unprocessable(CODE_REGION_NOT_REGISTERED,
                    "驻留区域未预先登记: " + request.regionCode());
        }

        Optional<ConsentRepository.GrantRow> latest =
                consentRepository.findLatestGrant(request.subjectKey(), request.purpose());
        GrantResponse response;
        if (latest.isPresent() && latest.get().status() == GrantStatus.ACTIVE) {
            String existingRegion = latest.get().regionCode();
            if (request.regionCode() != null && !request.regionCode().equals(existingRegion)) {
                throw ApiException.conflict(CODE_GRANT_REGION_IMMUTABLE,
                        "同一 epoch 的驻留区域不可修改: 当前=" + existingRegion + ", 请求=" + request.regionCode());
            }
            response = new GrantResponse(request.subjectKey(), request.purpose(),
                    latest.get().epoch(), GrantStatus.ACTIVE);
        } else {
            int nextEpoch = latest.map(row -> row.epoch() + 1).orElse(1);
            try {
                consentRepository.insertGrant(request.subjectKey(), request.purpose(), nextEpoch,
                        request.regionCode(), request.requestId());
            } catch (DuplicateKeyException concurrent) {
                // 并发授权同一代次：以已提交的行为准
                ConsentRepository.GrantRow committed =
                        consentRepository.findGrant(request.subjectKey(), request.purpose(), nextEpoch)
                                .orElseThrow(() -> concurrent);
                if (!Objects.equals(committed.regionCode(), request.regionCode())) {
                    throw ApiException.conflict(CODE_GRANT_REGION_IMMUTABLE,
                            "并发授权已登记不同区域: " + committed.regionCode());
                }
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
     * 写入：仅当前有效代次可写；写入必须携带与代次登记区域一致的区域路由；
     * 同代同 recordKey 同 payload 去重返回原记录，不同 payload 返回 409。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + request.payload()
                + "|" + Objects.toString(request.regionCode(), "");
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            RecordResponse snapshot = readSnapshot(replayed.get().responseBody(), RecordResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        requireRegionRoute(latest, request.regionCode());

        Optional<ConsentRepository.RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (existing.get().erasedAt() != null) {
                throw ApiException.gone(CODE_DATA_ERASED, "记录已清除，不可重复写入同一 recordKey");
            }
            if (!existing.get().payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(existing.get());
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), request.payload(), latest.regionCode(), request.requestId());
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
     */
    @Transactional
    public GrantResponse revoke(RevokeRequest request) {
        String fingerprint = OP_REVOKE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        consentRepository.findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        boolean revoked = consentRepository.revokeGrant(
                request.subjectKey(), request.purpose(), request.epoch(), Instant.now(clock));
        if (!revoked) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回");
        }
        GrantResponse response = new GrantResponse(request.subjectKey(), request.purpose(),
                request.epoch(), GrantStatus.REVOKED);
        storeSuccess(request.requestId(), OP_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 清除：仅当前有效代次的记录可清除；清除后内容置 NULL，读取返回 410，迁移整次 422。
     */
    @Transactional
    public EraseResponse erase(EraseRequest request) {
        String fingerprint = OP_ERASE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), EraseResponse.class);
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());

        consentRepository.findRecord(request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));

        Instant erasedAt = Instant.now(clock);
        boolean erased = consentRepository.eraseRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey(), erasedAt);
        if (!erased) {
            throw ApiException.conflict(CODE_DATA_ALREADY_ERASED, "记录已清除");
        }
        EraseResponse response = new EraseResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), request.recordKey(), erasedAt);
        storeSuccess(request.requestId(), OP_ERASE, fingerprint, response);
        return response;
    }

    /**
     * 查询：仅当前有效代次可查；撤回后返回 410，已清除返回 410，记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public RecordResponse read(String subjectKey, Purpose purpose, String recordKey) {
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        ConsentRepository.RecordRow record = consentRepository
                .findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
        if (record.erasedAt() != null) {
            throw ApiException.gone(CODE_DATA_ERASED, "记录已清除");
        }
        return toResponse(record);
    }

    private void requireGrantActive(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    /**
     * 区域路由校验：写入携带的区域必须与代次登记区域一致（均未登记也视为一致）。
     */
    private void requireRegionRoute(ConsentRepository.GrantRow grant, String requestRegion) {
        if (!Objects.equals(grant.regionCode(), requestRegion)) {
            throw ApiException.forbidden(CODE_REGION_ROUTE_MISMATCH,
                    "写入区域路由与授权代次登记区域不一致: 登记=" + grant.regionCode() + ", 请求=" + requestRegion);
        }
    }

    /**
     * 幂等重放检查：命中且参数一致返回原快照；参数不一致返回 409。
     */
    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
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
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
    }

    private RecordResponse toResponse(ConsentRepository.RecordRow row) {
        return new RecordResponse(row.subjectKey(), row.purpose(), row.epoch(), row.recordKey(), row.payload());
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
