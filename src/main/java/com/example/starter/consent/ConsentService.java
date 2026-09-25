package com.example.starter.consent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordViewResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.example.starter.consent.dto.ScopeCreateRequest;
import com.example.starter.consent.dto.ScopeResponse;
import com.example.starter.consent.dto.ScopeRevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权域服务：实现授权、子范围、写入、撤回与查询的业务规则及幂等语义。
 *
 * <p>幂等规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原结果，
 * 参数变更返回 409；失败请求不占用 requestId。写入重放不得绕过授权/子范围状态：
 * 即使 requestId 命中幂等记录，只要所属代次已整体撤回或子范围已独立撤回，仍返回 410。
 *
 * <p>并发规则：子范围创建、独立撤回、整体撤回与写入均以数据库事务提交顺序裁决。
 * 写入/建子范围/子范围撤回先对授权代次行加行锁（SELECT ... FOR UPDATE），
 * 命名子范围写入再对子范围行加锁，使撤回 UPDATE 与写入按提交顺序互斥：
 * 撤回先提交则该范围写入拒绝；写入先提交则撤回随后生效但不抹杀已成功写入的可见性历史。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_RECORD_SCOPE_CONFLICT = "RECORD_SCOPE_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_SCOPE_NOT_FOUND = "SCOPE_NOT_FOUND";
    static final String CODE_SCOPE_LABEL_REQUIRED = "SCOPE_LABEL_REQUIRED";
    static final String CODE_SCOPE_LABEL_CONFLICT = "SCOPE_LABEL_CONFLICT";
    static final String CODE_SCOPE_ALREADY_REVOKED = "SCOPE_ALREADY_REVOKED";
    static final String CODE_SCOPE_REVOKED = "SCOPE_REVOKED";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_SCOPE_CREATE = "SCOPE_CREATE";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_SCOPE_REVOKE = "SCOPE_REVOKE";
    private static final String OP_REVOKE = "REVOKE";

    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public ConsentService(ConsentRepository consentRepository,
                          IdempotencyRepository idempotencyRepository,
                          ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 授权：当前授权仍有效时返回原代次；撤回后或首次授权生成下一代（从 1 开始递增）。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        String fingerprint = OP_GRANT + "|" + request.subjectKey() + "|" + request.purpose();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
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
     * 创建子范围：仅在有效 epoch 内创建逻辑分区，不产生新 epoch；同 scopeKey 同标签返回原子范围，
     * 同 scopeKey 不同标签返回 409；epoch 已撤回返回 410；scopeKey 已独立撤回不可复用（410）。
     */
    @Transactional
    public ScopeResponse createScope(ScopeCreateRequest request) {
        String fingerprint = OP_SCOPE_CREATE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch() + "|" + request.scopeKey() + "|" + request.label();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), ScopeResponse.class);
        }

        requireGrantActiveForUpdate(request.subjectKey(), request.purpose(), request.epoch());
        ScopeResponse response = ensureScope(request.subjectKey(), request.purpose(), request.epoch(),
                request.scopeKey(), request.label(), request.requestId());
        storeSuccess(request.requestId(), OP_SCOPE_CREATE, fingerprint, response);
        return response;
    }

    /**
     * 写入：仅当前有效代次可写；带 scopeKey 时该子范围须在本代存在且未独立撤回，不存在时以非空标签创建。
     * 同代同 recordKey 同 payload 同子范围去重返回原记录，不同 payload 返回 409，不同子范围返回 409。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        String scopeKey = normalize(request.scopeKey());
        String label = normalize(request.label());
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + request.payload()
                + "|" + (scopeKey == null ? "" : scopeKey) + "|" + (label == null ? "" : label);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            RecordResponse snapshot = readSnapshot(replayed.get().responseBody(), RecordResponse.class);
            requireWritable(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch(), snapshot.scopeKey());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActiveForUpdate(latest.subjectKey(), latest.purpose(), latest.epoch());

        String resolvedScopeKey = null;
        if (scopeKey != null) {
            resolvedScopeKey = resolveScopeForWrite(request.subjectKey(), request.purpose(),
                    latest.epoch(), scopeKey, label, request.requestId());
        }

        Optional<ConsentRepository.RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            RecordResponse response = requireSameScopeAndPayload(existing.get(), request.payload(), resolvedScopeKey);
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    resolvedScopeKey, request.recordKey(), request.payload(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            ConsentRepository.RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            RecordResponse response = requireSameScopeAndPayload(committed, request.payload(), resolvedScopeKey);
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        RecordResponse response = new RecordResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), resolvedScopeKey, request.recordKey(), request.payload());
        storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 子范围独立撤回：仅当整体 epoch 仍有效时允许；撤回后该子范围立即 410，其他子范围与默认子范围不受影响。
     * 子范围不存在返回 404，已独立撤回返回 409，整体 epoch 已撤回返回 410。
     */
    @Transactional
    public ScopeResponse revokeScope(ScopeRevokeRequest request) {
        String fingerprint = OP_SCOPE_REVOKE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch() + "|" + request.scopeKey();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), ScopeResponse.class);
        }

        requireGrantActiveForUpdate(request.subjectKey(), request.purpose(), request.epoch());
        ConsentRepository.ScopeRow scope = consentRepository
                .findScope(request.subjectKey(), request.purpose(), request.epoch(), request.scopeKey())
                .orElseThrow(() -> ApiException.notFound(CODE_SCOPE_NOT_FOUND, "子范围不存在"));
        if (scope.status() == ScopeStatus.REVOKED) {
            throw ApiException.conflict(CODE_SCOPE_ALREADY_REVOKED, "子范围已独立撤回");
        }
        boolean revoked = consentRepository.revokeScope(
                request.subjectKey(), request.purpose(), request.epoch(), request.scopeKey());
        if (!revoked) {
            throw ApiException.conflict(CODE_SCOPE_ALREADY_REVOKED, "子范围已独立撤回");
        }
        ScopeResponse response = new ScopeResponse(request.subjectKey(), request.purpose(),
                request.epoch(), request.scopeKey(), scope.label(), ScopeStatus.REVOKED);
        storeSuccess(request.requestId(), OP_SCOPE_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 整体撤回：指定代次只允许从有效变为已撤回；撤回提交后旧代全部子范围（含已独立撤回者）一并 410，
     * 不区分子范围状态，不受子范围已撤回状态限制。
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
        boolean revoked = consentRepository.revokeGrant(request.subjectKey(), request.purpose(), request.epoch());
        if (!revoked) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回");
        }
        GrantResponse response = new GrantResponse(request.subjectKey(), request.purpose(),
                request.epoch(), GrantStatus.REVOKED);
        storeSuccess(request.requestId(), OP_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 单条查询：仅当前有效代次可查；整体撤回返回 410，所属子范围独立撤回同样返回 410，记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public RecordResponse read(String subjectKey, Purpose purpose, String recordKey) {
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        ConsentRepository.RecordRow record = consentRepository
                .findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
        requireScopeActive(subjectKey, purpose, latest.epoch(), record.scopeKey());
        return toResponse(record);
    }

    /**
     * 按 epoch 聚合查询：返回该代全部记录（不静默过滤），逐条标明所属子范围与是否可用；
     * 整体撤回或子范围独立撤回均使对应记录 usable=false。代次不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<RecordViewResponse> listEpochRecords(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        Map<String, ScopeStatus> scopeStatuses = consentRepository.listScopes(subjectKey, purpose, epoch).stream()
                .collect(Collectors.toMap(ConsentRepository.ScopeRow::scopeKey, ConsentRepository.ScopeRow::status));
        boolean grantActive = grant.status() == GrantStatus.ACTIVE;
        return consentRepository.listRecordsByEpoch(subjectKey, purpose, epoch).stream()
                .map(row -> new RecordViewResponse(row.subjectKey(), row.purpose(), row.epoch(),
                        row.scopeKey(), row.recordKey(), row.payload(),
                        grantActive && (row.scopeKey() == null
                                || scopeStatuses.getOrDefault(row.scopeKey(), ScopeStatus.ACTIVE) == ScopeStatus.ACTIVE)))
                .toList();
    }

    /**
     * 按 epoch 查询子范围清单及各自状态（不含默认子范围）；代次不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<ScopeResponse> listScopes(String subjectKey, Purpose purpose, int epoch) {
        consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        return consentRepository.listScopes(subjectKey, purpose, epoch).stream()
                .map(row -> new ScopeResponse(row.subjectKey(), row.purpose(), row.epoch(),
                        row.scopeKey(), row.label(), row.status()))
                .toList();
    }

    /**
     * 写入路径的子范围解析：已存在须为 ACTIVE（并持有行锁以与独立撤回按提交顺序互斥）；
     * 不存在时必须提供非空标签即时创建；已独立撤回的 scopeKey 拒绝复用。
     */
    private String resolveScopeForWrite(String subjectKey, Purpose purpose, int epoch,
                                        String scopeKey, String label, String requestId) {
        Optional<ConsentRepository.ScopeRow> locked =
                consentRepository.findScopeForUpdate(subjectKey, purpose, epoch, scopeKey);
        if (locked.isPresent()) {
            if (locked.get().status() == ScopeStatus.REVOKED) {
                throw ApiException.gone(CODE_SCOPE_REVOKED, "子范围已独立撤回，scopeKey 不可复用");
            }
            return scopeKey;
        }
        if (label == null) {
            throw ApiException.badRequest(CODE_SCOPE_LABEL_REQUIRED, "新建子范围的写入必须提供非空标签");
        }
        return ensureScope(subjectKey, purpose, epoch, scopeKey, label, requestId).scopeKey();
    }

    /**
     * 确保子范围存在且有效（调用方已持有代次行锁）：存在则校验状态与标签，不存在则创建。
     */
    private ScopeResponse ensureScope(String subjectKey, Purpose purpose, int epoch,
                                      String scopeKey, String label, String requestId) {
        Optional<ConsentRepository.ScopeRow> existing =
                consentRepository.findScope(subjectKey, purpose, epoch, scopeKey);
        if (existing.isPresent()) {
            ConsentRepository.ScopeRow scope = existing.get();
            if (scope.status() == ScopeStatus.REVOKED) {
                throw ApiException.gone(CODE_SCOPE_REVOKED, "子范围已独立撤回，scopeKey 不可复用");
            }
            if (!scope.label().equals(label)) {
                throw ApiException.conflict(CODE_SCOPE_LABEL_CONFLICT, "相同 scopeKey 已存在不同标签");
            }
            return toScopeResponse(scope);
        }
        try {
            consentRepository.insertScope(subjectKey, purpose, epoch, scopeKey, label, requestId);
        } catch (DuplicateKeyException concurrent) {
            // 并发创建同一 scopeKey：以已提交的行为准
            ConsentRepository.ScopeRow committed = consentRepository
                    .findScope(subjectKey, purpose, epoch, scopeKey)
                    .orElseThrow(() -> concurrent);
            if (committed.status() == ScopeStatus.REVOKED) {
                throw ApiException.gone(CODE_SCOPE_REVOKED, "子范围已独立撤回，scopeKey 不可复用");
            }
            if (!committed.label().equals(label)) {
                throw ApiException.conflict(CODE_SCOPE_LABEL_CONFLICT, "相同 scopeKey 已存在不同标签");
            }
            return toScopeResponse(committed);
        }
        return new ScopeResponse(subjectKey, purpose, epoch, scopeKey, label, ScopeStatus.ACTIVE);
    }

    private RecordResponse requireSameScopeAndPayload(ConsentRepository.RecordRow committed,
                                                      String payload, String scopeKey) {
        if (!committed.payload().equals(payload)) {
            throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
        }
        if (!Objects.equals(committed.scopeKey(), scopeKey)) {
            throw ApiException.conflict(CODE_RECORD_SCOPE_CONFLICT, "相同 recordKey 已归属其他子范围");
        }
        return toResponse(committed);
    }

    private void requireWritable(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        requireGrantActive(subjectKey, purpose, epoch);
        requireScopeActive(subjectKey, purpose, epoch, scopeKey);
    }

    private void requireGrantActive(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    /**
     * 代次有效性校验并持有行锁：使本事务与整体撤回 UPDATE 按提交顺序互斥。
     */
    private void requireGrantActiveForUpdate(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrantForUpdate(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    private void requireScopeActive(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        if (scopeKey == null) {
            return;
        }
        ConsentRepository.ScopeRow scope = consentRepository.findScope(subjectKey, purpose, epoch, scopeKey)
                .orElseThrow(() -> ApiException.notFound(CODE_SCOPE_NOT_FOUND, "子范围不存在"));
        if (scope.status() == ScopeStatus.REVOKED) {
            throw ApiException.gone(CODE_SCOPE_REVOKED, "子范围已独立撤回");
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
        return new RecordResponse(row.subjectKey(), row.purpose(), row.epoch(),
                row.scopeKey(), row.recordKey(), row.payload());
    }

    private ScopeResponse toScopeResponse(ConsentRepository.ScopeRow row) {
        return new ScopeResponse(row.subjectKey(), row.purpose(), row.epoch(),
                row.scopeKey(), row.label(), row.status());
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
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
