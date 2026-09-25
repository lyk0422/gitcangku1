package com.example.starter.consent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.AggregateResponse;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.example.starter.consent.dto.ScopeCreateRequest;
import com.example.starter.consent.dto.ScopeListResponse;
import com.example.starter.consent.dto.ScopeResponse;
import com.example.starter.consent.dto.ScopeRevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权域服务：实现授权、子范围、写入、撤回与查询的业务规则及幂等语义。
 *
 * <p>幂等规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原结果，
 * 参数变更返回 409；失败请求不占用 requestId。写入重放不得绕过授权与子范围状态：
 * 即使 requestId 命中幂等记录，只要所属代次或子范围已撤回，仍返回 410。
 *
 * <p>子范围规则：子范围是同一有效代次内的逻辑分区，不产生新代次；scopeKey 在同一 epoch
 * 内唯一，default 为保留的默认子范围。子范围独立撤回仅当整体 epoch 仍有效时允许；
 * 整体撤回使当前 epoch 下全部子范围一并返回 410。子范围创建、独立撤回与整体撤回、
 * 写入并发按数据库事务提交顺序裁决（行锁读取＋条件更新）。
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
    static final String CODE_SCOPE_REVOKED = "SCOPE_REVOKED";
    static final String CODE_SCOPE_ALREADY_REVOKED = "SCOPE_ALREADY_REVOKED";
    static final String CODE_SCOPE_LABEL_CONFLICT = "SCOPE_LABEL_CONFLICT";
    static final String CODE_INVALID_SCOPE_KEY = "INVALID_SCOPE_KEY";

    /** 默认子范围标识：未指定子范围的写入归入该范围，该值为保留值不可显式创建。 */
    static final String DEFAULT_SCOPE_KEY = "default";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";
    private static final String OP_SCOPE_CREATE = "SCOPE_CREATE";
    private static final String OP_SCOPE_REVOKE = "SCOPE_REVOKE";

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
     * 子范围创建：仅当前有效代次可创建；同代同 scopeKey 同标签去重返回原子范围，
     * 标签不同返回 409；已撤回子范围的 scopeKey 不可复用，返回 410。
     */
    @Transactional
    public ScopeResponse createScope(ScopeCreateRequest request) {
        String fingerprint = OP_SCOPE_CREATE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.scopeKey() + "|" + request.label();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            ScopeResponse snapshot = readSnapshot(replayed.get().responseBody(), ScopeResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch());
            return snapshot;
        }

        if (DEFAULT_SCOPE_KEY.equals(request.scopeKey())) {
            throw ApiException.badRequest(CODE_INVALID_SCOPE_KEY, "scopeKey 为保留值: " + DEFAULT_SCOPE_KEY);
        }
        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrantForUpdate(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());

        Optional<ConsentRepository.ScopeRow> existing = consentRepository.findScopeForUpdate(
                request.subjectKey(), request.purpose(), latest.epoch(), request.scopeKey());
        if (existing.isPresent()) {
            ScopeResponse response = reuseExistingScope(existing.get(), request.label());
            storeSuccess(request.requestId(), OP_SCOPE_CREATE, fingerprint, response);
            return response;
        }

        try {
            consentRepository.insertScope(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.scopeKey(), request.label(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发创建同一 scopeKey：以已提交的行为准
            ConsentRepository.ScopeRow committed = consentRepository.findScope(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.scopeKey())
                    .orElseThrow(() -> concurrent);
            ScopeResponse response = reuseExistingScope(committed, request.label());
            storeSuccess(request.requestId(), OP_SCOPE_CREATE, fingerprint, response);
            return response;
        }

        ScopeResponse response = new ScopeResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), request.scopeKey(), request.label(), GrantStatus.ACTIVE);
        storeSuccess(request.requestId(), OP_SCOPE_CREATE, fingerprint, response);
        return response;
    }

    /**
     * 子范围独立撤回：仅当整体 epoch 仍有效时允许；撤回后该子范围立即返回 410，
     * 同 epoch 内其他子范围与默认子范围不受影响。
     */
    @Transactional
    public ScopeResponse revokeScope(ScopeRevokeRequest request) {
        String fingerprint = OP_SCOPE_REVOKE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch() + "|" + request.scopeKey();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), ScopeResponse.class);
        }

        ConsentRepository.GrantRow grant = consentRepository
                .findGrantForUpdate(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "整体授权已撤回，子范围不可单独撤回");
        }
        ConsentRepository.ScopeRow scope = consentRepository.findScopeForUpdate(
                        request.subjectKey(), request.purpose(), request.epoch(), request.scopeKey())
                .orElseThrow(() -> ApiException.notFound(CODE_SCOPE_NOT_FOUND, "子范围不存在"));
        boolean revoked = consentRepository.revokeScope(
                request.subjectKey(), request.purpose(), request.epoch(), request.scopeKey());
        if (!revoked) {
            throw ApiException.conflict(CODE_SCOPE_ALREADY_REVOKED, "子范围已撤回");
        }
        ScopeResponse response = new ScopeResponse(scope.subjectKey(), scope.purpose(), scope.epoch(),
                scope.scopeKey(), scope.label(), GrantStatus.REVOKED);
        storeSuccess(request.requestId(), OP_SCOPE_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 写入：仅当前有效代次可写；可指定子范围，缺省归入默认子范围。
     * 同代同 recordKey 同 payload 且同子范围去重返回原记录，payload 或子范围不同返回 409。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        String scopeKey = normalizeScopeKey(request.scopeKey());
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + scopeKey + "|" + request.payload();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            RecordResponse snapshot = readSnapshot(replayed.get().responseBody(), RecordResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch());
            requireScopeActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch(), snapshot.scopeKey());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrantForUpdate(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        requireScopeActiveForUpdate(latest.subjectKey(), latest.purpose(), latest.epoch(), scopeKey);

        Optional<ConsentRepository.RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            RecordResponse response = reuseExistingRecord(existing.get(), scopeKey, request.payload());
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), scopeKey, request.payload(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            ConsentRepository.RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            RecordResponse response = reuseExistingRecord(committed, scopeKey, request.payload());
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        RecordResponse response = new RecordResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), request.recordKey(), scopeKey, request.payload());
        storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 撤回：指定代次只允许从有效变为已撤回；撤回提交后旧代查询立即返回 410，写入被拒绝。
     * 整体撤回使当前 epoch 下全部子范围一并返回 410，不区分子范围状态。
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
     * 查询：仅当前有效代次可查；代次撤回返回 410，所属子范围已撤回同样返回 410，
     * 记录不存在返回 404。
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
     * 聚合查询：按主体、用途、代次列出全部记录，标明每条记录所属子范围及是否可用；
     * 已撤回子范围的记录仍返回并标记不可用，不做静默过滤。代次已撤回返回 410。
     */
    @Transactional(readOnly = true)
    public AggregateResponse aggregate(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
        Map<String, ConsentRepository.ScopeRow> scopes = consentRepository
                .listScopes(subjectKey, purpose, epoch).stream()
                .collect(Collectors.toMap(ConsentRepository.ScopeRow::scopeKey, Function.identity()));
        List<AggregateResponse.AggregateEntry> entries = new ArrayList<>();
        for (ConsentRepository.RecordRow record : consentRepository.listRecords(subjectKey, purpose, epoch)) {
            entries.add(new AggregateResponse.AggregateEntry(record.recordKey(), record.scopeKey(),
                    record.payload(), scopeAvailable(scopes, record.scopeKey())));
        }
        return new AggregateResponse(subjectKey, purpose, epoch, entries);
    }

    /**
     * 子范围清单：按代次列出全部子范围（含默认子范围）及各自状态；
     * 整体代次已撤回时所有子范围状态均为 REVOKED 且不可用。
     */
    @Transactional(readOnly = true)
    public ScopeListResponse listScopes(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        boolean epochActive = grant.status() == GrantStatus.ACTIVE;
        List<ScopeListResponse.ScopeEntry> entries = new ArrayList<>();
        entries.add(new ScopeListResponse.ScopeEntry(DEFAULT_SCOPE_KEY, null,
                epochActive ? GrantStatus.ACTIVE : GrantStatus.REVOKED, epochActive));
        for (ConsentRepository.ScopeRow scope : consentRepository.listScopes(subjectKey, purpose, epoch)) {
            boolean available = epochActive && scope.status() == GrantStatus.ACTIVE;
            entries.add(new ScopeListResponse.ScopeEntry(scope.scopeKey(), scope.label(),
                    available ? GrantStatus.ACTIVE : GrantStatus.REVOKED, available));
        }
        return new ScopeListResponse(subjectKey, purpose, epoch, entries);
    }

    private ScopeResponse reuseExistingScope(ConsentRepository.ScopeRow existing, String label) {
        if (existing.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_SCOPE_REVOKED, "子范围已撤回，scopeKey 不可复用");
        }
        if (!existing.label().equals(label)) {
            throw ApiException.conflict(CODE_SCOPE_LABEL_CONFLICT, "相同 scopeKey 已存在不同标签");
        }
        return new ScopeResponse(existing.subjectKey(), existing.purpose(), existing.epoch(),
                existing.scopeKey(), existing.label(), existing.status());
    }

    private RecordResponse reuseExistingRecord(ConsentRepository.RecordRow existing,
                                               String scopeKey, String payload) {
        if (!existing.payload().equals(payload)) {
            throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
        }
        if (!existing.scopeKey().equals(scopeKey)) {
            throw ApiException.conflict(CODE_RECORD_SCOPE_CONFLICT, "相同 recordKey 已存在于其他子范围");
        }
        return toResponse(existing);
    }

    private String normalizeScopeKey(String scopeKey) {
        return scopeKey == null || scopeKey.isBlank() ? DEFAULT_SCOPE_KEY : scopeKey;
    }

    private void requireGrantActive(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    /**
     * 校验子范围当前可用：默认子范围随整体代次有效即可用；显式子范围不存在返回 404，
     * 已撤回返回 410。
     */
    private void requireScopeActive(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        if (DEFAULT_SCOPE_KEY.equals(scopeKey)) {
            return;
        }
        ConsentRepository.ScopeRow scope = consentRepository.findScope(subjectKey, purpose, epoch, scopeKey)
                .orElseThrow(() -> ApiException.notFound(CODE_SCOPE_NOT_FOUND, "子范围不存在"));
        if (scope.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_SCOPE_REVOKED, "子范围已撤回");
        }
    }

    /**
     * 行锁校验子范围可用：与子范围撤回互斥，按事务提交顺序裁决写入与撤回。
     */
    private void requireScopeActiveForUpdate(String subjectKey, Purpose purpose, int epoch, String scopeKey) {
        if (DEFAULT_SCOPE_KEY.equals(scopeKey)) {
            return;
        }
        ConsentRepository.ScopeRow scope = consentRepository
                .findScopeForUpdate(subjectKey, purpose, epoch, scopeKey)
                .orElseThrow(() -> ApiException.notFound(CODE_SCOPE_NOT_FOUND, "子范围不存在"));
        if (scope.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_SCOPE_REVOKED, "子范围已撤回");
        }
    }

    private boolean scopeAvailable(Map<String, ConsentRepository.ScopeRow> scopes, String scopeKey) {
        if (DEFAULT_SCOPE_KEY.equals(scopeKey)) {
            return true;
        }
        ConsentRepository.ScopeRow scope = scopes.get(scopeKey);
        return scope != null && scope.status() == GrantStatus.ACTIVE;
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
                row.recordKey(), row.scopeKey(), row.payload());
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
