package com.example.starter.consent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.BatchFailureItem;
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
    static final String CODE_EPOCH_SUPERSEDED = "EPOCH_SUPERSEDED";
    static final String CODE_EPOCH_AHEAD = "EPOCH_AHEAD";
    static final String CODE_SUBJECT_EPOCH_CONFLICT = "SUBJECT_EPOCH_CONFLICT";
    static final String CODE_DUPLICATE_QUERY_ITEM = "DUPLICATE_QUERY_ITEM";

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
     * 固定授权代次的原子批量查询：
     *
     * <ul>
     *   <li>同一主体在本请求中只能指定一个 epoch，（主体、代次、记录键）三元组不得重复，否则整批 400；</li>
     *   <li>每项必须命中该主体该用途的当前 ACTIVE 代次且记录存在；未知主体/记录 404、旧代/已撤回 410、超前代次 409；</li>
     *   <li>授权行以 SELECT … FOR UPDATE 锁定，与撤回按事务提交顺序裁决；任一失败整批拒绝，错误仅含索引与稳定码；</li>
     *   <li>成功后参数指纹与结果快照与 requestId 同事务保存；同键改参 409，失败不占键；</li>
     *   <li>同键同参重放保持首次顺序与内容，但必须重新核对全部代次，任一已撤回或被新代替代则整批 410。</li>
     * </ul>
     */
    @Transactional
    public BatchQueryResponse batchQuery(BatchQueryRequest request) {
        List<BatchQueryItem> items = request.items();
        String fingerprint = batchFingerprint(request);

        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);

        // 结构性参数校验：同主体多个 epoch、三元组重复
        List<BatchFailureItem> failures = new ArrayList<>(validateBatchItems(items));
        HashSet<String> epochConflictedSubjects = collectEpochConflictedSubjects(items);

        // 结构性冲突的主体不再做代次裁决（其期望代次本身有歧义）；其余主体按固定顺序加锁裁决
        Map<String, Integer> epochBySubject = new LinkedHashMap<>();
        for (BatchQueryItem item : items) {
            epochBySubject.putIfAbsent(item.subjectKey(), item.expectedEpoch());
        }
        Map<String, SubjectDecision> decisions = new LinkedHashMap<>();
        List<String> subjects = new ArrayList<>(epochBySubject.keySet());
        subjects.sort(String::compareTo);
        for (String subjectKey : subjects) {
            if (epochConflictedSubjects.contains(subjectKey)) {
                continue;
            }
            decisions.put(subjectKey,
                    decideSubject(request.purpose(), subjectKey, epochBySubject.get(subjectKey)));
        }

        // 收集全部失败项：主体级失败作用于该主体全部项；其余项校验记录存在性
        for (int i = 0; i < items.size(); i++) {
            BatchQueryItem item = items.get(i);
            SubjectDecision decision = decisions.get(item.subjectKey());
            if (decision == null) {
                continue;
            }
            if (decision.failureCode() != null) {
                failures.add(new BatchFailureItem(i, decision.failureCode()));
            } else {
                Optional<ConsentRepository.RecordRow> record = consentRepository.findRecord(
                        item.subjectKey(), request.purpose(), decision.epoch(), item.recordKey());
                if (record.isEmpty()) {
                    failures.add(new BatchFailureItem(i, CODE_RECORD_NOT_FOUND));
                }
            }
        }
        failures.sort((a, b) -> Integer.compare(a.index(), b.index()));

        if (failures.isEmpty()) {
            if (replayed.isPresent()) {
                // 重放：全部指定代次已在本次事务内重新核对通过，直接返回首次快照，保持首次顺序与内容
                return readSnapshot(replayed.get().responseBody(), BatchQueryResponse.class);
            }
            // 首次成功：按输入顺序在同一事务一致视图内组装结果并与 requestId 同事务保存
            List<RecordResponse> results = new ArrayList<>(items.size());
            for (BatchQueryItem item : items) {
                int epoch = decisions.get(item.subjectKey()).epoch();
                ConsentRepository.RecordRow row = consentRepository
                        .findRecord(item.subjectKey(), request.purpose(), epoch, item.recordKey())
                        .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
                results.add(toResponse(row));
            }
            BatchQueryResponse response = new BatchQueryResponse(results);
            storeSuccess(request.requestId(), OP_BATCH_QUERY, fingerprint, response);
            return response;
        }

        // 整批拒绝：事务回滚；非重放的失败不写入幂等记录，不占用 requestId。
        // HTTP 状态取输入顺序首个失败项。
        throw new BatchRejectedException(statusOfFirstFailure(failures), failures);
    }

    private List<BatchFailureItem> validateBatchItems(List<BatchQueryItem> items) {
        List<BatchFailureItem> failures = new ArrayList<>();
        Map<String, Integer> epochBySubject = new LinkedHashMap<>();
        HashSet<String> triples = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            BatchQueryItem item = items.get(i);
            Integer existingEpoch = epochBySubject.putIfAbsent(item.subjectKey(), item.expectedEpoch());
            if (existingEpoch != null && !existingEpoch.equals(item.expectedEpoch())) {
                failures.add(new BatchFailureItem(i, CODE_SUBJECT_EPOCH_CONFLICT));
            }
            String triple = item.subjectKey() + "|" + item.expectedEpoch() + "|" + item.recordKey();
            if (!triples.add(triple)) {
                failures.add(new BatchFailureItem(i, CODE_DUPLICATE_QUERY_ITEM));
            }
        }
        return failures;
    }

    private HashSet<String> collectEpochConflictedSubjects(List<BatchQueryItem> items) {
        Map<String, Integer> epochBySubject = new LinkedHashMap<>();
        HashSet<String> conflicted = new HashSet<>();
        for (BatchQueryItem item : items) {
            Integer existingEpoch = epochBySubject.putIfAbsent(item.subjectKey(), item.expectedEpoch());
            if (existingEpoch != null && !existingEpoch.equals(item.expectedEpoch())) {
                conflicted.add(item.subjectKey());
            }
        }
        return conflicted;
    }

    /**
     * 在当前事务内锁定并裁决单个主体的期望代次：
     * 未知主体/代次行不存在且不超前→404；期望代次已撤回→410；仍是 ACTIVE 但已被新代替代→410；大于最新代次→409。
     */
    private SubjectDecision decideSubject(Purpose purpose, String subjectKey, int expectedEpoch) {
        Optional<ConsentRepository.GrantRow> expected =
                consentRepository.lockGrant(subjectKey, purpose, expectedEpoch);
        if (expected.isEmpty()) {
            Optional<ConsentRepository.GrantRow> latest =
                    consentRepository.lockLatestGrant(subjectKey, purpose);
            if (latest.isPresent() && expectedEpoch > latest.get().epoch()) {
                return new SubjectDecision(expectedEpoch, CODE_EPOCH_AHEAD);
            }
            // 无任何授权行（未知主体）或代次不超前但行缺失：均按授权不存在处理
            return new SubjectDecision(expectedEpoch, CODE_GRANT_NOT_FOUND);
        }
        if (expected.get().status() == GrantStatus.REVOKED) {
            return new SubjectDecision(expectedEpoch, CODE_CONSENT_REVOKED);
        }
        ConsentRepository.GrantRow latest = consentRepository
                .lockLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        if (latest.epoch() > expectedEpoch) {
            // 期望代次仍 ACTIVE 但已不是当前最新代（被新代替代）
            return new SubjectDecision(expectedEpoch, CODE_EPOCH_SUPERSEDED);
        }
        return new SubjectDecision(expectedEpoch, null);
    }

    private HttpStatus statusOfFirstFailure(List<BatchFailureItem> sortedFailures) {
        return switch (sortedFailures.get(0).code()) {
            case CODE_GRANT_NOT_FOUND, CODE_RECORD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CODE_EPOCH_AHEAD, CODE_REQUEST_ID_CONFLICT -> HttpStatus.CONFLICT;
            case CODE_SUBJECT_EPOCH_CONFLICT, CODE_DUPLICATE_QUERY_ITEM -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.GONE;
        };
    }

    private String batchFingerprint(BatchQueryRequest request) {
        StringBuilder builder = new StringBuilder(OP_BATCH_QUERY)
                .append('|').append(request.purpose()).append('|');
        for (BatchQueryItem item : request.items()) {
            builder.append(item.subjectKey()).append(',')
                    .append(item.expectedEpoch()).append(',')
                    .append(item.recordKey()).append(';');
        }
        // 50 项时明文指纹可能超过指纹列长度：取 SHA-256 十六进制摘要，重放计算方式一致
        return sha256Hex(builder.toString());
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 单个主体的代次裁决结果。
     *
     * @param epoch       裁决通过时的当前 ACTIVE 代次
     * @param failureCode 失败稳定码，{@code null} 表示通过
     */
    private record SubjectDecision(int epoch, String failureCode) {
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
