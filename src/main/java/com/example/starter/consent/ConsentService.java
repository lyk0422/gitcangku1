package com.example.starter.consent;

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
import com.example.starter.consent.migration.CatalogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权域服务：实现授权、写入、撤回与查询的业务规则及幂等语义。
 *
 * <p>用途由用途目录管理：授权与写入前必须在当前目录代次中存在 ACTIVE 用途，
 * 记录属性必须落在该用途处理范围内。目录迁移期间通过目录条目行锁与迁移事务串行，
 * 保证任一数据行始终只有一个活动用途归属。
 *
 * <p>幂等规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原结果，
 * 参数变更返回 409；失败请求不占用 requestId。写入重放不得绕过授权状态：
 * 即使 requestId 命中幂等记录，只要所属代次已撤回或已迁移，仍返回 410。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_GRANT_ALREADY_MIGRATED = "GRANT_ALREADY_MIGRATED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_CONSENT_MIGRATED = "CONSENT_MIGRATED";
    static final String CODE_PURPOSE_NOT_ACTIVE = "PURPOSE_NOT_ACTIVE";
    static final String CODE_RECORD_ATTRIBUTE_OUT_OF_RANGE = "RECORD_ATTRIBUTE_OUT_OF_RANGE";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";

    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final CatalogRepository catalogRepository;
    private final ObjectMapper objectMapper;

    public ConsentService(ConsentRepository consentRepository,
                          IdempotencyRepository idempotencyRepository,
                          CatalogRepository catalogRepository,
                          ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.catalogRepository = catalogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 授权：当前授权仍有效时返回原代次；撤回后或首次授权生成下一代（从 1 开始递增）。
     * 用途必须在当前目录代次中处于 ACTIVE。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        String fingerprint = OP_GRANT + "|" + request.subjectKey() + "|" + request.purpose();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        long latestGeneration = catalogRepository.latestGeneration();
        // 锁定当前目录中的用途条目：与拆分该用途的目录迁移互斥
        CatalogRepository.EntryRow purposeEntry = catalogRepository
                .lockEntry(latestGeneration, request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_PURPOSE_NOT_ACTIVE, "用途在当前目录中不存在: "
                        + request.purpose()));
        if (!"ACTIVE".equals(purposeEntry.status())) {
            throw ApiException.gone(CODE_PURPOSE_NOT_ACTIVE, "用途在当前目录中已失效: " + request.purpose());
        }

        Optional<ConsentRepository.GrantRow> latest =
                consentRepository.findLatestGrant(request.subjectKey(), request.purpose());
        GrantResponse response;
        if (latest.isPresent() && latest.get().status() == GrantStatus.ACTIVE) {
            response = toGrantResponse(latest.get());
        } else {
            int nextEpoch = latest.map(row -> row.epoch() + 1).orElse(1);
            try {
                consentRepository.insertGrant(request.subjectKey(), request.purpose(), nextEpoch,
                        request.requestId(), latestGeneration);
            } catch (DuplicateKeyException concurrent) {
                // 并发授权同一代次：以已提交的行为准
                ConsentRepository.GrantRow committed =
                        consentRepository.findGrant(request.subjectKey(), request.purpose(), nextEpoch)
                                .orElseThrow(() -> concurrent);
                response = toGrantResponse(committed);
                storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
                return response;
            }
            response = new GrantResponse(request.subjectKey(), request.purpose(),
                    nextEpoch, latestGeneration, GrantStatus.ACTIVE);
        }
        storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
        return response;
    }

    /**
     * 写入：仅当前有效代次可写；属性须落在用途处理范围内；同代同 recordKey 同 payload 去重。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + request.payload() + "|" + request.recordAttribute();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            RecordResponse snapshot = readSnapshot(replayed.get().responseBody(), RecordResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch());
            return snapshot;
        }

        long latestGeneration = catalogRepository.latestGeneration();
        CatalogRepository.EntryRow purposeEntry = catalogRepository
                .lockEntry(latestGeneration, request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_PURPOSE_NOT_ACTIVE, "用途在当前目录中不存在: "
                        + request.purpose()));
        if (!"ACTIVE".equals(purposeEntry.status())) {
            throw ApiException.gone(CODE_PURPOSE_NOT_ACTIVE, "用途在当前目录中已失效: " + request.purpose());
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        // 用途在当前目录代次仍为 ACTIVE 即可写：未参与本次拆分的沿用用途无需重新授权；
        // 被拆分（SUPERSEDED）的旧用途已在上面的条目状态检查中被拒绝，不会混读旧新用途
        if (!purposeEntry.range().contains(request.recordAttribute())) {
            throw ApiException.badRequest(CODE_RECORD_ATTRIBUTE_OUT_OF_RANGE, "记录属性超出用途处理范围");
        }

        Optional<ConsentRepository.RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (!existing.get().payload().equals(request.payload())
                    || existing.get().recordAttribute() != request.recordAttribute()) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT,
                        "相同 recordKey 已存在不同内容或属性");
            }
            RecordResponse response = toResponse(existing.get());
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), request.payload(), request.recordAttribute(),
                    request.requestId(), latestGeneration);
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            ConsentRepository.RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (!committed.payload().equals(request.payload())
                    || committed.recordAttribute() != request.recordAttribute()) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT,
                        "相同 recordKey 已存在不同内容或属性");
            }
            RecordResponse response = toResponse(committed);
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        RecordResponse response = new RecordResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), latestGeneration, request.recordKey(), request.recordAttribute(),
                request.payload());
        storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 撤回：指定代次只允许从 ACTIVE 变为 REVOKED；已迁移授权不可撤回，历史撤回链不改写。
     */
    @Transactional
    public GrantResponse revoke(RevokeRequest request) {
        String fingerprint = OP_REVOKE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        ConsentRepository.GrantRow grant = consentRepository
                .findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.MIGRATED) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_MIGRATED, "授权已随用途迁移，不可撤回");
        }
        boolean revoked = consentRepository.revokeGrant(request.subjectKey(), request.purpose(), request.epoch());
        if (!revoked) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回");
        }
        GrantResponse response = toGrantResponse(
                new ConsentRepository.GrantRow(request.subjectKey(), request.purpose(), request.epoch(),
                        GrantStatus.REVOKED, grant.version() + 1, grant.catalogGeneration()));
        storeSuccess(request.requestId(), OP_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 单条查询：未显式固定目录代次时按当前最新目录代次读取。
     */
    @Transactional(readOnly = true)
    public RecordResponse read(String subjectKey, String purpose, String recordKey) {
        return readWithinGeneration(catalogRepository.latestGeneration(), subjectKey, purpose, recordKey);
    }

    /**
     * 在固定目录代次内读取：授权必须有效，且该用途在固定目录代次中仍为 ACTIVE。
     * 未参与拆分、沿用（ACTIVE）的用途其历史数据可继续读取；被拆分（SUPERSEDED）的旧用途读不到，
     * 从而防止混读旧新用途。
     */
    RecordResponse readWithinGeneration(long catalogGeneration, String subjectKey, String purpose, String recordKey) {
        boolean purposeActive = catalogRepository.findEntry(catalogGeneration, purpose)
                .map(entry -> "ACTIVE".equals(entry.status()))
                .orElse(false);
        if (!purposeActive) {
            throw ApiException.notFound(CODE_RECORD_NOT_FOUND, "固定目录代次内不存在该用途数据");
        }
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        return consentRepository.findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
    }

    private void requireGrantActive(String subjectKey, String purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
        if (grant.status() == GrantStatus.MIGRATED) {
            throw ApiException.gone(CODE_CONSENT_MIGRATED, "授权已随用途迁移");
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

    private GrantResponse toGrantResponse(ConsentRepository.GrantRow row) {
        return new GrantResponse(row.subjectKey(), row.purpose(), row.epoch(),
                row.catalogGeneration(), row.status());
    }

    private RecordResponse toResponse(ConsentRepository.RecordRow row) {
        return new RecordResponse(row.subjectKey(), row.purpose(), row.epoch(), row.catalogGeneration(),
                row.recordKey(), row.recordAttribute(), row.payload());
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
