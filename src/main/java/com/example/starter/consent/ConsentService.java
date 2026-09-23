package com.example.starter.consent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.BatchItemFailure;
import com.example.starter.consent.dto.BatchQueryItem;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权域服务：实现授权、写入、撤回与查询的业务规则及幂等语义。
 *
 * <p>幂等规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原结果，
 * 参数变更返回 409；失败请求不占用 requestId。写入重放不得绕过授权状态：
 * 即使 requestId 命中幂等记录，只要所属代次已撤回，仍返回 410。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_GRANT_SUPERSEDED = "GRANT_SUPERSEDED";
    static final String CODE_EPOCH_AHEAD = "EPOCH_AHEAD";
    static final String CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";
    private static final String OP_BATCH_QUERY = "BATCH_QUERY";

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
     * 写入：仅当前有效代次可写；同代同 recordKey 同 payload 去重返回原记录，不同 payload 返回 409。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + request.payload();
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

        Optional<ConsentRepository.RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (!existing.get().payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(existing.get());
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

    /**
     * 固定授权代次原子批量查询：
     *
     * <p>请求级规则（违反返回 400）：1～50 项；同一主体本请求内只能指定一个 epoch；
     * （主体, epoch, recordKey）三元组不得重复。
     *
     * <p>逐项裁决（在同一事务一致视图内，先按主体排序锁定最新授权行）：
     * 未知主体 404；记录不存在 404；指定代次已撤回 410；指定代次已被新代替代 410；
     * epoch 大于最新代次 409。任一失败整批拒绝，仅返回失败项索引与稳定错误码，
     * 整体 HTTP 取输入顺序首个失败项的状态。
     *
     * <p>幂等：成功后参数指纹与结果快照同事务保存；同键改参 409，失败不占键。
     * 同键同参重放返回首次快照的顺序与内容，但必须重新加锁核对全部指定代次：
     * 任一已撤回或已被新代替代，整批 410，不返回缓存内容，也不自动转读新代。
     */
    @Transactional
    public BatchQueryResponse batchQuery(BatchQueryRequest request) {
        validateBatchItems(request);
        String fingerprint = buildBatchFingerprint(request);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            BatchQueryResponse snapshot = readSnapshot(replayed.get().responseBody(), BatchQueryResponse.class);
            reverifyBatchEpochs(request);
            return snapshot;
        }

        // 按主体键排序后依次锁定各主体最新授权行，避免并发事务交叉加锁导致死锁，
        // 同时与撤回事务的行级 UPDATE 互斥，实现按事务提交顺序裁决。
        List<String> subjects = request.items().stream()
                .map(BatchQueryItem::subjectKey)
                .distinct()
                .sorted()
                .toList();
        Map<String, ConsentRepository.GrantRow> latestBySubject = new HashMap<>();
        for (String subjectKey : subjects) {
            consentRepository.findLatestGrantForUpdate(subjectKey, request.purpose())
                    .ifPresent(row -> latestBySubject.put(subjectKey, row));
        }

        List<BatchItemFailure> failures = new ArrayList<>();
        List<RecordResponse> results = new ArrayList<>(request.items().size());
        for (int i = 0; i < request.items().size(); i++) {
            BatchQueryItem item = request.items().get(i);
            ConsentRepository.GrantRow latest = latestBySubject.get(item.subjectKey());
            if (latest == null) {
                failures.add(new BatchItemFailure(i, CODE_GRANT_NOT_FOUND));
                results.add(null);
                continue;
            }
            if (item.expectedEpoch() > latest.epoch()) {
                failures.add(new BatchItemFailure(i, CODE_EPOCH_AHEAD));
                results.add(null);
                continue;
            }
            if (item.expectedEpoch() < latest.epoch()) {
                // 旧代次：锁定旧代行，与并发撤回按提交顺序裁决；撤回后重授场景旧代为 REVOKED
                String code = consentRepository
                        .findGrantForUpdate(item.subjectKey(), request.purpose(), item.expectedEpoch())
                        .filter(row -> row.status() == GrantStatus.REVOKED)
                        .isPresent() ? CODE_CONSENT_REVOKED : CODE_GRANT_SUPERSEDED;
                failures.add(new BatchItemFailure(i, code));
                results.add(null);
                continue;
            }
            if (latest.status() == GrantStatus.REVOKED) {
                failures.add(new BatchItemFailure(i, CODE_CONSENT_REVOKED));
                results.add(null);
                continue;
            }
            ConsentRepository.RecordRow record = consentRepository.findRecord(
                    item.subjectKey(), request.purpose(), item.expectedEpoch(), item.recordKey()).orElse(null);
            if (record == null) {
                failures.add(new BatchItemFailure(i, CODE_RECORD_NOT_FOUND));
                results.add(null);
                continue;
            }
            results.add(toResponse(record));
        }

        if (!failures.isEmpty()) {
            // 失败不占键：不写幂等记录，事务回滚仅释放锁，不产生任何业务变更
            throw new BatchQueryException(batchFailureStatus(failures.get(0).code()), failures);
        }

        BatchQueryResponse response = new BatchQueryResponse(List.copyOf(results));
        storeSuccess(request.requestId(), OP_BATCH_QUERY, fingerprint, response);
        return response;
    }

    /**
     * 请求级参数校验：同一主体本请求内只能指定一个 epoch，三元组不得重复。
     */
    private void validateBatchItems(BatchQueryRequest request) {
        Map<String, Integer> epochBySubject = new HashMap<>();
        Set<String> triples = new HashSet<>();
        for (BatchQueryItem item : request.items()) {
            Integer existingEpoch = epochBySubject.putIfAbsent(item.subjectKey(), item.expectedEpoch());
            if (existingEpoch != null && !existingEpoch.equals(item.expectedEpoch())) {
                throw ApiException.badRequest(CODE_INVALID_ARGUMENT,
                        "同一主体在本请求中只能指定一个 epoch: " + item.subjectKey());
            }
            String triple = item.subjectKey() + "|" + item.expectedEpoch() + "|" + item.recordKey();
            if (!triples.add(triple)) {
                throw ApiException.badRequest(CODE_INVALID_ARGUMENT, "查询三元组重复: " + triple);
            }
        }
    }

    private String buildBatchFingerprint(BatchQueryRequest request) {
        StringBuilder builder = new StringBuilder(OP_BATCH_QUERY)
                .append('|').append(request.purpose());
        for (BatchQueryItem item : request.items()) {
            builder.append('|').append(item.subjectKey())
                    .append('|').append(item.expectedEpoch())
                    .append('|').append(item.recordKey());
        }
        return builder.toString();
    }

    /**
     * 重放前重新加锁核对快照中全部指定代次：已撤回或已被新代替代的主体，
     * 其全部查询项整批 410，绝不返回缓存内容。
     */
    private void reverifyBatchEpochs(BatchQueryRequest request) {
        Map<String, String> failureCodeBySubject = new HashMap<>();
        List<String> subjects = request.items().stream()
                .map(BatchQueryItem::subjectKey)
                .distinct()
                .sorted()
                .toList();
        for (String subjectKey : subjects) {
            int epoch = request.items().stream()
                    .filter(item -> item.subjectKey().equals(subjectKey))
                    .map(BatchQueryItem::expectedEpoch)
                    .findFirst().orElseThrow();
            // 与首次查询保持相同加锁顺序：先锁最新代行，再锁指定代行，避免锁环
            ConsentRepository.GrantRow latest = consentRepository
                    .findLatestGrantForUpdate(subjectKey, request.purpose()).orElse(null);
            ConsentRepository.GrantRow grant = consentRepository
                    .findGrantForUpdate(subjectKey, request.purpose(), epoch).orElse(null);
            if (grant == null || grant.status() == GrantStatus.REVOKED) {
                failureCodeBySubject.put(subjectKey, CODE_CONSENT_REVOKED);
            } else if (latest == null || latest.epoch() != epoch || latest.status() != GrantStatus.ACTIVE) {
                failureCodeBySubject.put(subjectKey, CODE_GRANT_SUPERSEDED);
            }
        }
        if (failureCodeBySubject.isEmpty()) {
            return;
        }
        List<BatchItemFailure> failures = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            String code = failureCodeBySubject.get(request.items().get(i).subjectKey());
            if (code != null) {
                failures.add(new BatchItemFailure(i, code));
            }
        }
        throw new BatchQueryException(HttpStatus.GONE, failures);
    }

    private HttpStatus batchFailureStatus(String code) {
        return switch (code) {
            case CODE_GRANT_NOT_FOUND, CODE_RECORD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CODE_CONSENT_REVOKED, CODE_GRANT_SUPERSEDED -> HttpStatus.GONE;
            case CODE_EPOCH_AHEAD -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
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
