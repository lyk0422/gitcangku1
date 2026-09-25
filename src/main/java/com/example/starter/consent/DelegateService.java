package com.example.starter.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.consent.DelegateRepository.BlockRow;
import com.example.starter.consent.DelegateRepository.DelegateRow;
import com.example.starter.consent.DelegateRepository.QueryItemRow;
import com.example.starter.consent.DelegateRepository.QueryRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.DelegateBlockResponse;
import com.example.starter.consent.dto.DelegateCreateRequest;
import com.example.starter.consent.dto.DelegateRenewRequest;
import com.example.starter.consent.dto.DelegateRenewResponse;
import com.example.starter.consent.dto.DelegateResponse;
import com.example.starter.consent.dto.DelegateRevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权代理委托域服务：委托创建、批量续签、撤销、代理批量查询与历史查询。
 *
 * <p>幂等规则：delegateKey 为主体、代理、授权代次、规范化用途、UTC 区间与版本的内容指纹，
 * 同键重放返回原委托，失败不占键；续签与撤销复用 requestId 幂等表；
 * 批量查询成功快照以 requestId 固化，失败不占用 requestId，仅留阻断审计。
 *
 * <p>顺序裁决：委托、续签、撤销、用途迁移与批量查询通过 {@link ConsentLock} 串行，
 * 且锁在事务提交之后释放，保证按提交顺序裁决；快照固化委托与授权版本，后续撤销/迁移不影响。
 */
@Service
public class DelegateService {

    static final String CODE_DELEGATION_PURPOSES_EMPTY = "DELEGATION_PURPOSES_EMPTY";
    static final String CODE_DELEGATION_SELF = "DELEGATION_SELF";
    static final String CODE_DELEGATION_INTERVAL_INVALID = "DELEGATION_INTERVAL_INVALID";
    static final String CODE_DELEGATION_GRANT_UNAVAILABLE = "DELEGATION_GRANT_UNAVAILABLE";
    static final String CODE_DELEGATION_NOT_FOUND = "DELEGATION_NOT_FOUND";
    static final String CODE_DELEGATION_ALREADY_REVOKED = "DELEGATION_ALREADY_REVOKED";
    static final String CODE_DELEGATION_RENEWAL_CONFLICT = "DELEGATION_RENEWAL_CONFLICT";
    static final String CODE_BATCH_QUERY_BLOCKED = "BATCH_QUERY_BLOCKED";
    static final String CODE_QUERY_NOT_FOUND = "QUERY_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    /** 批次阻断逐主体原因码（稳定，供客户端区分）。 */
    static final String REASON_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String REASON_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String REASON_DELEGATION_NOT_FOUND = "DELEGATION_NOT_FOUND";
    static final String REASON_DELEGATION_PURPOSE_NOT_COVERED = "DELEGATION_PURPOSE_NOT_COVERED";
    static final String REASON_DELEGATION_EPOCH_MISMATCH = "DELEGATION_EPOCH_MISMATCH";
    static final String REASON_DELEGATION_REVOKED = "DELEGATION_REVOKED";
    static final String REASON_DELEGATION_NOT_YET_VALID = "DELEGATION_NOT_YET_VALID";
    static final String REASON_DELEGATION_EXPIRED = "DELEGATION_EXPIRED";
    static final String REASON_DELEGATION_VERSION_CONFLICT = "DELEGATION_VERSION_CONFLICT";

    private static final String OP_DELEGATE_RENEW = "DELEGATE_RENEW";
    private static final String OP_DELEGATE_REVOKE = "DELEGATE_REVOKE";

    private final ConsentRepository consentRepository;
    private final DelegateRepository delegateRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ConsentLock consentLock;
    private final Clock clock;

    public DelegateService(ConsentRepository consentRepository,
                           DelegateRepository delegateRepository,
                           IdempotencyRepository idempotencyRepository,
                           ObjectMapper objectMapper,
                           org.springframework.transaction.PlatformTransactionManager transactionManager,
                           ConsentLock consentLock,
                           Clock clock) {
        this.consentRepository = consentRepository;
        this.delegateRepository = delegateRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.consentLock = consentLock;
        this.clock = clock;
    }

    /**
     * 创建委托：用途为空或代理为本人返回 422；主体每个用途须存在当前有效授权。
     * delegateKey 为内容指纹，同键重放返回原委托，失败不占键。
     */
    public DelegateResponse create(DelegateCreateRequest request) {
        consentLock.lock();
        try {
            return transactionTemplate.execute(status -> doCreate(request));
        } finally {
            consentLock.unlock();
        }
    }

    private DelegateResponse doCreate(DelegateCreateRequest request) {
        List<Purpose> purposes = normalizePurposes(request.purposes());
        if (request.subjectKey().equals(request.delegateId())) {
            throw ApiException.unprocessable(CODE_DELEGATION_SELF, "代理人不得为主体本人");
        }
        requireValidInterval(request.validFrom(), request.validTo());

        Map<Purpose, Integer> epochs = new LinkedHashMap<>();
        for (Purpose purpose : purposes) {
            ConsentRepository.GrantRow grant = consentRepository
                    .findLatestGrant(request.subjectKey(), purpose)
                    .filter(row -> row.status() == GrantStatus.ACTIVE)
                    .orElseThrow(() -> ApiException.unprocessable(CODE_DELEGATION_GRANT_UNAVAILABLE,
                            "主体用途 " + purpose + " 缺少当前有效授权"));
            epochs.put(purpose, grant.epoch());
        }

        String delegateKey = fingerprint(request.subjectKey(), request.delegateId(),
                purposes, epochs, request.validFrom(), request.validTo(), request.delegateVersion());
        Optional<DelegateRow> existing = delegateRepository.findByKey(delegateKey);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        DelegateRow row = new DelegateRow(delegateKey, request.subjectKey(), request.delegateId(),
                purposes, epochs, request.validFrom(), request.validTo(),
                request.delegateVersion(), DelegateStatus.ACTIVE);
        try {
            delegateRepository.insert(row);
        } catch (DuplicateKeyException concurrent) {
            // 并发同键创建：以已提交的委托为准
            return toResponse(delegateRepository.findByKey(delegateKey)
                    .orElseThrow(() -> concurrent));
        }
        return toResponse(row);
    }

    /**
     * 批量续签：先校验每份旧委托存在、有效且版本匹配，任一冲突整批不生效（409 逐条列原因）。
     * 续签成功后旧委托撤销，新委托版本递增、区间更新，授权代次绑定保持不变。
     */
    public DelegateRenewResponse renew(DelegateRenewRequest request) {
        consentLock.lock();
        try {
            return transactionTemplate.execute(status -> doRenew(request));
        } finally {
            consentLock.unlock();
        }
    }

    private DelegateRenewResponse doRenew(DelegateRenewRequest request) {
        String fingerprint = OP_DELEGATE_RENEW + "|" + renewFingerprint(request);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), DelegateRenewResponse.class);
        }

        for (DelegateRenewRequest.RenewItem item : request.items()) {
            requireValidInterval(item.validFrom(), item.validTo());
        }

        List<DelegateRow> olds = new ArrayList<>();
        List<RejectionReason> conflicts = new ArrayList<>();
        for (DelegateRenewRequest.RenewItem item : request.items()) {
            Optional<DelegateRow> row = delegateRepository.findByKey(item.delegateKey());
            if (row.isEmpty()) {
                conflicts.add(new RejectionReason(item.delegateKey(), REASON_DELEGATION_NOT_FOUND));
            } else if (row.get().status() != DelegateStatus.ACTIVE) {
                conflicts.add(new RejectionReason(item.delegateKey(), CODE_DELEGATION_ALREADY_REVOKED));
            } else if (row.get().delegateVersion() != item.expectedVersion()) {
                conflicts.add(new RejectionReason(item.delegateKey(), REASON_DELEGATION_VERSION_CONFLICT));
            } else {
                olds.add(row.get());
            }
        }
        if (!conflicts.isEmpty()) {
            conflicts.sort(Comparator.comparing(RejectionReason::target));
            throw new BatchRejectionException(HttpStatus.CONFLICT, CODE_DELEGATION_RENEWAL_CONFLICT,
                    "批量续签存在冲突，整批不生效", conflicts);
        }

        List<DelegateRenewResponse.RenewedItem> renewed = new ArrayList<>();
        for (DelegateRow old : olds) {
            DelegateRenewRequest.RenewItem item = request.items().stream()
                    .filter(candidate -> candidate.delegateKey().equals(old.delegateKey()))
                    .findFirst()
                    .orElseThrow();
            int newVersion = old.delegateVersion() + 1;
            String newKey = fingerprint(old.subjectKey(), old.delegateId(), old.purposes(),
                    old.epochs(), item.validFrom(), item.validTo(), newVersion);
            delegateRepository.revoke(old.delegateKey());
            delegateRepository.insert(new DelegateRow(newKey, old.subjectKey(), old.delegateId(),
                    old.purposes(), old.epochs(), item.validFrom(), item.validTo(),
                    newVersion, DelegateStatus.ACTIVE));
            renewed.add(new DelegateRenewResponse.RenewedItem(old.delegateKey(), newKey, newVersion));
        }
        DelegateRenewResponse response = new DelegateRenewResponse(renewed);
        storeSuccess(request.requestId(), OP_DELEGATE_RENEW, fingerprint, response);
        return response;
    }

    /**
     * 撤销委托：只允许从有效变为已撤销；撤销只影响后续查询，已生成快照不受影响。
     */
    public DelegateResponse revoke(DelegateRevokeRequest request) {
        consentLock.lock();
        try {
            return transactionTemplate.execute(status -> doRevoke(request));
        } finally {
            consentLock.unlock();
        }
    }

    private DelegateResponse doRevoke(DelegateRevokeRequest request) {
        String fingerprint = OP_DELEGATE_REVOKE + "|" + request.delegateKey();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), DelegateResponse.class);
        }

        DelegateRow row = delegateRepository.findByKey(request.delegateKey())
                .orElseThrow(() -> ApiException.notFound(CODE_DELEGATION_NOT_FOUND, "委托不存在"));
        if (!delegateRepository.revoke(request.delegateKey())) {
            throw ApiException.conflict(CODE_DELEGATION_ALREADY_REVOKED, "委托已撤销");
        }
        DelegateResponse response = toResponse(new DelegateRow(row.delegateKey(), row.subjectKey(),
                row.delegateId(), row.purposes(), row.epochs(), row.validFrom(), row.validTo(),
                row.delegateVersion(), DelegateStatus.REVOKED));
        storeSuccess(request.requestId(), OP_DELEGATE_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 代理批量查询：逐主体校验当前授权代次与该代理有效委托；任一缺失、过期或已撤销
     * 整批 403、记录阻断审计且不返回任何数据；全部通过则在同一事务内生成固化快照。
     */
    public BatchQueryResponse batchQuery(BatchQueryRequest request) {
        consentLock.lock();
        try {
            List<String> subjectKeys = new ArrayList<>(new LinkedHashSet<>(request.subjectKeys()));
            String fingerprint = "DQUERY|" + request.delegateId() + "|" + request.purpose()
                    + "|" + String.join(",", subjectKeys);

            Object result = transactionTemplate.execute(status -> {
                Optional<QueryRow> existing = delegateRepository.findQuery(request.requestId());
                if (existing.isPresent()) {
                    if (!existing.get().paramsFingerprint().equals(fingerprint)) {
                        throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
                    }
                    return toQueryResponse(existing.get(),
                            delegateRepository.findQueryItems(request.requestId()));
                }

                Instant now = clock.instant();
                List<RejectionReason> reasons = new ArrayList<>();
                List<ResolvedSubject> resolved = new ArrayList<>();
                for (String subjectKey : subjectKeys) {
                    resolveSubject(subjectKey, request.delegateId(), request.purpose(), now, resolved)
                            .ifPresent(reasons::add);
                }
                if (!reasons.isEmpty()) {
                    reasons.sort(Comparator.comparing(RejectionReason::target));
                    // 校验阶段未做任何写入：返回阻断描述，由事务外在独立事务中落审计
                    return new BlockedBatch(request.requestId(), request.delegateId(),
                            request.purpose(), subjectKeys, reasons);
                }

                QueryRow queryRow = new QueryRow(request.requestId(), request.delegateId(),
                        request.purpose(), fingerprint);
                List<QueryItemRow> items = new ArrayList<>();
                for (ResolvedSubject subject : resolved) {
                    for (ConsentRepository.RecordRow record : consentRepository.findRecords(
                            subject.subjectKey(), request.purpose(), subject.epoch())) {
                        items.add(new QueryItemRow(request.requestId(), subject.subjectKey(), subject.epoch(),
                                subject.delegation().delegateKey(), subject.delegation().delegateVersion(),
                                record.recordKey(), record.payload()));
                    }
                }
                delegateRepository.insertQuery(queryRow);
                items.forEach(delegateRepository::insertQueryItem);
                return toQueryResponse(queryRow, items);
            });

            if (result instanceof BlockedBatch blocked) {
                transactionTemplate.executeWithoutResult(status ->
                        delegateRepository.insertBlock(blocked.requestId(), blocked.delegateId(),
                                blocked.purpose(), String.join(",", blocked.subjectKeys()),
                                writeJson(blocked.reasons())));
                throw new BatchRejectionException(HttpStatus.FORBIDDEN, CODE_BATCH_QUERY_BLOCKED,
                        "批量查询被阻断，未返回任何数据", blocked.reasons());
            }
            return (BatchQueryResponse) result;
        } finally {
            consentLock.unlock();
        }
    }

    /**
     * 查询批量查询快照：返回固化的授权代次、委托指纹与版本及当时记录，后续撤销与迁移不影响。
     */
    @Transactional(readOnly = true)
    public BatchQueryResponse getQuerySnapshot(String queryId) {
        QueryRow query = delegateRepository.findQuery(queryId)
                .orElseThrow(() -> ApiException.notFound(CODE_QUERY_NOT_FOUND, "批量查询快照不存在"));
        return toQueryResponse(query, delegateRepository.findQueryItems(queryId));
    }

    /**
     * 查询委托历史：按主体与代理过滤，包含已撤销与历史版本。
     */
    @Transactional(readOnly = true)
    public List<DelegateResponse> history(String subjectKey, String delegateId) {
        return delegateRepository.findHistory(subjectKey, delegateId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询批次阻断审计：按代理过滤，按阻断标识升序。
     */
    @Transactional(readOnly = true)
    public List<DelegateBlockResponse> blocks(String delegateId) {
        return delegateRepository.findBlocks(delegateId).stream()
                .map(this::toBlockResponse)
                .toList();
    }

    /**
     * 逐主体判定：返回空表示通过并加入 resolved，否则返回阻断原因。
     * 判定顺序：授权存在 → 授权有效 → 委托存在 → 用途覆盖 → 代次匹配 → 未撤销 → 有效期。
     */
    private Optional<RejectionReason> resolveSubject(String subjectKey, String delegateId, Purpose purpose,
                                                     Instant now, List<ResolvedSubject> resolved) {
        Optional<ConsentRepository.GrantRow> latest = consentRepository.findLatestGrant(subjectKey, purpose);
        if (latest.isEmpty()) {
            return Optional.of(new RejectionReason(subjectKey, REASON_GRANT_NOT_FOUND));
        }
        if (latest.get().status() == GrantStatus.REVOKED) {
            return Optional.of(new RejectionReason(subjectKey, REASON_CONSENT_REVOKED));
        }
        int currentEpoch = latest.get().epoch();

        List<DelegateRow> delegations = delegateRepository.findBySubjectAndDelegate(subjectKey, delegateId);
        List<DelegateRow> covering = delegations.stream()
                .filter(row -> row.purposes().contains(purpose))
                .toList();
        if (covering.isEmpty()) {
            return Optional.of(new RejectionReason(subjectKey,
                    delegations.isEmpty() ? REASON_DELEGATION_NOT_FOUND : REASON_DELEGATION_PURPOSE_NOT_COVERED));
        }
        List<DelegateRow> epochMatched = covering.stream()
                .filter(row -> Objects.equals(row.epochs().get(purpose), currentEpoch))
                .toList();
        if (epochMatched.isEmpty()) {
            return Optional.of(new RejectionReason(subjectKey, REASON_DELEGATION_EPOCH_MISMATCH));
        }
        List<DelegateRow> active = epochMatched.stream()
                .filter(row -> row.status() == DelegateStatus.ACTIVE)
                .toList();
        if (active.isEmpty()) {
            return Optional.of(new RejectionReason(subjectKey, REASON_DELEGATION_REVOKED));
        }
        List<DelegateRow> valid = active.stream()
                .filter(row -> !now.isBefore(row.validFrom()) && now.isBefore(row.validTo()))
                .toList();
        if (valid.isEmpty()) {
            boolean notYet = active.stream().anyMatch(row -> now.isBefore(row.validFrom()));
            return Optional.of(new RejectionReason(subjectKey,
                    notYet ? REASON_DELEGATION_NOT_YET_VALID : REASON_DELEGATION_EXPIRED));
        }
        resolved.add(new ResolvedSubject(subjectKey, currentEpoch, valid.get(0)));
        return Optional.empty();
    }

    private List<Purpose> normalizePurposes(Set<Purpose> purposes) {
        // 规范化：去重后按用途名称升序，避免依赖枚举声明顺序
        List<Purpose> normalized = purposes == null ? List.of()
                : purposes.stream().sorted(Comparator.comparing(Enum::name)).toList();
        if (normalized.isEmpty()) {
            throw ApiException.unprocessable(CODE_DELEGATION_PURPOSES_EMPTY, "用途集合不能为空");
        }
        return normalized;
    }

    private void requireValidInterval(Instant validFrom, Instant validTo) {
        if (validFrom == null || validTo == null || !validFrom.isBefore(validTo)) {
            throw ApiException.unprocessable(CODE_DELEGATION_INTERVAL_INVALID,
                    "有效期区间必须满足 validFrom < validTo");
        }
    }

    /**
     * delegateKey 指纹：主体、代理、授权代次、规范化用途、UTC 区间与版本的 SHA-256。
     */
    private String fingerprint(String subjectKey, String delegateId, List<Purpose> purposes,
                               Map<Purpose, Integer> epochs, Instant validFrom, Instant validTo, int version) {
        String material = String.join("|", subjectKey, delegateId,
                DelegateRepository.formatPurposes(purposes),
                DelegateRepository.formatEpochs(new TreeMap<>(epochs)),
                validFrom.toString(), validTo.toString(), String.valueOf(version));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String renewFingerprint(DelegateRenewRequest request) {
        StringBuilder sb = new StringBuilder();
        for (DelegateRenewRequest.RenewItem item : request.items()) {
            sb.append(item.delegateKey()).append(':').append(item.expectedVersion())
                    .append(':').append(item.validFrom()).append(':').append(item.validTo()).append(';');
        }
        return sb.toString();
    }

    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
        return row;
    }

    private void storeSuccess(String requestId, String operation, String fingerprint, Object response) {
        try {
            idempotencyRepository.insert(requestId, operation, fingerprint, writeJson(response));
        } catch (DuplicateKeyException concurrent) {
            // 并发同 requestId：校验已提交快照参数一致，否则视为冲突
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
    }

    private DelegateResponse toResponse(DelegateRow row) {
        return new DelegateResponse(row.delegateKey(), row.subjectKey(), row.delegateId(),
                row.purposes(), new TreeMap<>(row.epochs()), row.validFrom(), row.validTo(),
                row.delegateVersion(), row.status());
    }

    private BatchQueryResponse toQueryResponse(QueryRow query, List<QueryItemRow> items) {
        Map<String, BatchQueryResponse.SubjectItem> bySubject = new LinkedHashMap<>();
        Map<String, List<BatchQueryResponse.RecordItem>> recordsBySubject = new LinkedHashMap<>();
        for (QueryItemRow item : items) {
            bySubject.putIfAbsent(item.subjectKey(), new BatchQueryResponse.SubjectItem(
                    item.subjectKey(), item.epoch(), item.delegateKey(), item.delegateVersion(), List.of()));
            recordsBySubject.computeIfAbsent(item.subjectKey(), key -> new ArrayList<>())
                    .add(new BatchQueryResponse.RecordItem(item.recordKey(), item.payload()));
        }
        List<BatchQueryResponse.SubjectItem> subjects = new ArrayList<>();
        bySubject.forEach((subjectKey, item) -> subjects.add(new BatchQueryResponse.SubjectItem(
                item.subjectKey(), item.epoch(), item.delegateKey(), item.delegateVersion(),
                recordsBySubject.getOrDefault(subjectKey, List.of()))));
        return new BatchQueryResponse(query.queryId(), query.delegateId(), query.purpose(), subjects);
    }

    private DelegateBlockResponse toBlockResponse(BlockRow row) {
        List<RejectionReason> reasons;
        try {
            reasons = objectMapper.readValue(row.reasonsJson(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("阻断原因反序列化失败", e);
        }
        List<String> subjectKeys = row.subjectKeys().isEmpty() ? List.of()
                : List.of(row.subjectKeys().split(","));
        return new DelegateBlockResponse(row.id(), row.requestId(), row.delegateId(),
                row.purpose(), subjectKeys, reasons);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
    }

    private <T> T readSnapshot(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }

    /**
     * 批量查询校验失败时在事务内传递的阻断信息，事务回滚后由外层落审计并抛 403。
     */
    private record BlockedBatch(String requestId, String delegateId, Purpose purpose,
                                List<String> subjectKeys, List<RejectionReason> reasons) {
    }

    /**
     * 批量查询中单个主体的判定结果：当前授权代次与命中的委托。
     */
    private record ResolvedSubject(String subjectKey, int epoch, DelegateRow delegation) {
    }
}
