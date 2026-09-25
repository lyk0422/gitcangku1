package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.starter.consent.DelegateRepository.DelegateRow;
import com.example.starter.consent.DelegateRepository.SnapshotRow;
import com.example.starter.consent.DelegateRepository.SnapshotSubjectRow;
import com.example.starter.consent.DelegateRepository.VersionRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.DelegateCreateRequest;
import com.example.starter.consent.dto.DelegateHistoryResponse;
import com.example.starter.consent.dto.DelegateQueryRequest;
import com.example.starter.consent.dto.DelegateQueryResponse;
import com.example.starter.consent.dto.DelegateQuerySnapshotResponse;
import com.example.starter.consent.dto.DelegateRecordView;
import com.example.starter.consent.dto.DelegateRenewItem;
import com.example.starter.consent.dto.DelegateRenewRequest;
import com.example.starter.consent.dto.DelegateRenewResponse;
import com.example.starter.consent.dto.DelegateResponse;
import com.example.starter.consent.dto.DelegateRevokeRequest;
import com.example.starter.consent.dto.DelegateStatusResponse;
import com.example.starter.consent.dto.DelegateSubjectResult;
import com.example.starter.consent.dto.DelegateVersionView;
import com.example.starter.consent.dto.SnapshotSubjectView;
import com.example.starter.consent.dto.SubjectDenial;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权代理委托域服务：委托创建、批量续签、撤销、代理批量查询与历史/快照查询。
 *
 * <p>裁决顺序：委托、续签、撤销与批量查询在 JVM 内按提交顺序串行裁决（锁覆盖整个事务，
 * 含提交），并由数据库主键与乐观版本更新兜底。delegateKey 指纹含主体、代理、授权代次、
 * 规范化用途、UTC 区间和版本；同键同指纹重放返回原结果，失败不占键。
 *
 * <p>幂等规则与授权域一致：成功结果与业务变更同事务保存；失败请求不占用 requestId。
 */
@Service
public class DelegateService {

    static final String CODE_DELEGATE_PURPOSES_EMPTY = "DELEGATE_PURPOSES_EMPTY";
    static final String CODE_DELEGATE_SELF_DELEGATION = "DELEGATE_SELF_DELEGATION";
    static final String CODE_DELEGATE_INVALID_WINDOW = "DELEGATE_INVALID_WINDOW";
    static final String CODE_DELEGATE_DUPLICATE_IN_BATCH = "DELEGATE_DUPLICATE_IN_BATCH";
    static final String CODE_DELEGATE_NOT_FOUND = "DELEGATE_NOT_FOUND";
    static final String CODE_DELEGATE_KEY_CONFLICT = "DELEGATE_KEY_CONFLICT";
    static final String CODE_DELEGATE_VERSION_CONFLICT = "DELEGATE_VERSION_CONFLICT";
    static final String CODE_DELEGATE_ALREADY_REVOKED = "DELEGATE_ALREADY_REVOKED";
    static final String CODE_DELEGATE_REVOKED = "DELEGATE_REVOKED";
    static final String CODE_DELEGATE_PURPOSE_NOT_COVERED = "DELEGATE_PURPOSE_NOT_COVERED";
    static final String CODE_DELEGATE_EPOCH_STALE = "DELEGATE_EPOCH_STALE";
    static final String CODE_DELEGATE_NOT_YET_VALID = "DELEGATE_NOT_YET_VALID";
    static final String CODE_DELEGATE_EXPIRED = "DELEGATE_EXPIRED";
    static final String CODE_QUERY_NOT_FOUND = "QUERY_NOT_FOUND";
    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    private static final String OP_DELEGATE_RENEW = "DELEGATE_RENEW";
    private static final String OP_DELEGATE_REVOKE = "DELEGATE_REVOKE";
    private static final String OP_DELEGATE_QUERY = "DELEGATE_QUERY";

    private final DelegateRepository delegateRepository;
    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 委托域操作串行化锁：保证委托、续签、撤销与批量查询按提交顺序裁决。 */
    private final ReentrantLock orderLock = new ReentrantLock();

    public DelegateService(DelegateRepository delegateRepository,
                           ConsentRepository consentRepository,
                           IdempotencyRepository idempotencyRepository,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager,
                           Clock clock) {
        this.delegateRepository = delegateRepository;
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 创建委托：用途为空或代理为本人返回 422；绑定各用途当前有效授权代次。
     */
    public DelegateResponse create(DelegateCreateRequest request) {
        List<Purpose> purposes = normalizePurposes(request.purposes());
        if (purposes.isEmpty()) {
            throw ApiException.unprocessableEntity(CODE_DELEGATE_PURPOSES_EMPTY, "委托用途集合不能为空");
        }
        if (request.subjectKey().equals(request.agentKey())) {
            throw ApiException.unprocessableEntity(CODE_DELEGATE_SELF_DELEGATION, "代理人不得为主体本人");
        }
        requireValidWindow(request.validFrom(), request.validTo());

        orderLock.lock();
        try {
            return transactionTemplate.execute(tx -> doCreate(request, purposes));
        } finally {
            orderLock.unlock();
        }
    }

    private DelegateResponse doCreate(DelegateCreateRequest request, List<Purpose> purposes) {
        Map<Purpose, Integer> epochs = resolveActiveEpochs(request.subjectKey(), purposes);
        String fingerprint = delegateFingerprint(request.subjectKey(), request.agentKey(), epochs,
                purposes, request.validFrom(), request.validTo(), 1);

        Optional<DelegateRow> existing = delegateRepository.findDelegate(request.delegateKey());
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_DELEGATE_KEY_CONFLICT, "同一 delegateKey 参数指纹不一致");
            }
            return toResponse(existing.get(), 1);
        }

        try {
            delegateRepository.insertDelegate(request.delegateKey(), request.subjectKey(),
                    request.agentKey(), fingerprint);
            for (Purpose purpose : purposes) {
                delegateRepository.insertVersion(request.delegateKey(), 1, purpose, epochs.get(purpose),
                        request.validFrom().toEpochMilli(), request.validTo().toEpochMilli());
            }
        } catch (DuplicateKeyException concurrent) {
            // 并发创建同一委托键：以已提交的行为准，指纹一致则返回原结果
            DelegateRow committed = delegateRepository.findDelegate(request.delegateKey())
                    .orElseThrow(() -> concurrent);
            if (!committed.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_DELEGATE_KEY_CONFLICT, "同一 delegateKey 参数指纹不一致");
            }
            return toResponse(committed, 1);
        }
        return new DelegateResponse(request.delegateKey(), request.subjectKey(), request.agentKey(),
                purposes, epochs, request.validFrom(), request.validTo(), 1, DelegateStatus.ACTIVE);
    }

    /**
     * 批量续签：先校验每份旧委托版本，任一冲突整批不生效；成功后版本递增并采用新有效期。
     */
    public DelegateRenewResponse renew(DelegateRenewRequest request) {
        List<DelegateRenewItem> items = new ArrayList<>(request.items());
        items.sort(Comparator.comparing(DelegateRenewItem::delegateKey));
        for (DelegateRenewItem item : items) {
            requireValidWindow(item.validFrom(), item.validTo());
        }
        long distinctKeys = items.stream().map(DelegateRenewItem::delegateKey).distinct().count();
        if (distinctKeys != items.size()) {
            throw ApiException.unprocessableEntity(CODE_DELEGATE_DUPLICATE_IN_BATCH, "同一批次内委托键重复");
        }

        orderLock.lock();
        try {
            return transactionTemplate.execute(tx -> doRenew(request.requestId(), items));
        } finally {
            orderLock.unlock();
        }
    }

    private DelegateRenewResponse doRenew(String requestId, List<DelegateRenewItem> items) {
        String fingerprint = OP_DELEGATE_RENEW + "|" + items.stream()
                .map(item -> item.delegateKey() + "=" + item.expectedVersion()
                        + "@" + item.validFrom().toEpochMilli() + "-" + item.validTo().toEpochMilli())
                .collect(Collectors.joining(","));
        Optional<IdempotencyRow> replayed = checkReplay(requestId, fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), DelegateRenewResponse.class);
        }

        // 先校验全部旧委托版本，任一冲突整批不生效
        List<DelegateRow> delegates = new ArrayList<>();
        for (DelegateRenewItem item : items) {
            DelegateRow delegate = delegateRepository.findDelegate(item.delegateKey())
                    .orElseThrow(() -> ApiException.notFound(CODE_DELEGATE_NOT_FOUND, "委托不存在"));
            if (delegate.status() == DelegateStatus.REVOKED) {
                throw ApiException.conflict(CODE_DELEGATE_ALREADY_REVOKED, "委托已撤销");
            }
            if (delegate.currentVersion() != item.expectedVersion()) {
                throw ApiException.conflict(CODE_DELEGATE_VERSION_CONFLICT, "委托版本与预期不一致");
            }
            delegates.add(delegate);
        }

        List<DelegateResponse> renewed = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DelegateRenewItem item = items.get(i);
            DelegateRow delegate = delegates.get(i);
            if (!delegateRepository.advanceVersion(item.delegateKey(), item.expectedVersion())) {
                throw ApiException.conflict(CODE_DELEGATE_VERSION_CONFLICT, "委托版本与预期不一致");
            }
            int newVersion = item.expectedVersion() + 1;
            List<VersionRow> previous = delegateRepository.findVersion(item.delegateKey(), item.expectedVersion());
            for (VersionRow row : previous) {
                delegateRepository.insertVersion(item.delegateKey(), newVersion, row.purpose(), row.epoch(),
                        item.validFrom().toEpochMilli(), item.validTo().toEpochMilli());
            }
            renewed.add(toResponse(delegate, newVersion));
        }
        DelegateRenewResponse response = new DelegateRenewResponse(renewed);
        storeSuccess(requestId, OP_DELEGATE_RENEW, fingerprint, response);
        return response;
    }

    /**
     * 撤销委托：只影响后续查询，已生成查询快照固化委托与授权版本。
     */
    public DelegateStatusResponse revoke(DelegateRevokeRequest request) {
        orderLock.lock();
        try {
            return transactionTemplate.execute(tx -> doRevoke(request));
        } finally {
            orderLock.unlock();
        }
    }

    private DelegateStatusResponse doRevoke(DelegateRevokeRequest request) {
        String fingerprint = OP_DELEGATE_REVOKE + "|" + request.delegateKey();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), DelegateStatusResponse.class);
        }

        DelegateRow delegate = delegateRepository.findDelegate(request.delegateKey())
                .orElseThrow(() -> ApiException.notFound(CODE_DELEGATE_NOT_FOUND, "委托不存在"));
        if (delegate.status() == DelegateStatus.REVOKED) {
            throw ApiException.conflict(CODE_DELEGATE_ALREADY_REVOKED, "委托已撤销");
        }
        if (!delegateRepository.revokeDelegate(request.delegateKey())) {
            throw ApiException.conflict(CODE_DELEGATE_ALREADY_REVOKED, "委托已撤销");
        }
        DelegateStatusResponse response = new DelegateStatusResponse(
                request.delegateKey(), delegate.currentVersion(), DelegateStatus.REVOKED);
        storeSuccess(request.requestId(), OP_DELEGATE_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 代理批量查询：每个主体的当前授权代次均须有该代理有效委托且覆盖请求用途；
     * 任一缺失、过期或已撤销返回 403 并稳定列出主体原因，不返回任何数据、不留快照。
     */
    public DelegateQueryResponse query(DelegateQueryRequest request) {
        List<String> subjects = request.subjects().stream()
                .distinct().sorted().toList();
        List<Purpose> purposes = normalizePurposes(request.purposes());

        orderLock.lock();
        try {
            return transactionTemplate.execute(tx -> doQuery(request, subjects, purposes));
        } finally {
            orderLock.unlock();
        }
    }

    private DelegateQueryResponse doQuery(DelegateQueryRequest request, List<String> subjects,
                                          List<Purpose> purposes) {
        String fingerprint = OP_DELEGATE_QUERY + "|" + request.agentKey()
                + "|" + String.join(",", subjects)
                + "|" + purposes.stream().map(Purpose::name).collect(Collectors.joining(","));
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            // 重放返回固化的快照结果，不受后续续签、撤销或代次迁移影响
            return readSnapshot(replayed.get().responseBody(), DelegateQueryResponse.class);
        }

        long nowMs = clock.millis();
        List<SubjectDenial> denials = new ArrayList<>();
        List<DelegateSubjectResult> results = new ArrayList<>();
        for (String subjectKey : subjects) {
            Map<Purpose, Integer> currentEpochs = new LinkedHashMap<>();
            String grantDenial = null;
            for (Purpose purpose : purposes) {
                Optional<ConsentRepository.GrantRow> latest =
                        consentRepository.findLatestGrant(subjectKey, purpose);
                if (latest.isEmpty()) {
                    grantDenial = CODE_GRANT_NOT_FOUND;
                    break;
                }
                if (latest.get().status() == GrantStatus.REVOKED) {
                    grantDenial = CODE_CONSENT_REVOKED;
                    break;
                }
                currentEpochs.put(purpose, latest.get().epoch());
            }
            if (grantDenial != null) {
                denials.add(new SubjectDenial(subjectKey, grantDenial, denialMessage(grantDenial)));
                continue;
            }

            List<DelegateRow> candidates =
                    delegateRepository.findDelegatesFor(subjectKey, request.agentKey());
            if (candidates.isEmpty()) {
                denials.add(new SubjectDenial(subjectKey, CODE_DELEGATE_NOT_FOUND,
                        denialMessage(CODE_DELEGATE_NOT_FOUND)));
                continue;
            }
            List<DelegateRow> active = candidates.stream()
                    .filter(row -> row.status() == DelegateStatus.ACTIVE).toList();
            if (active.isEmpty()) {
                denials.add(new SubjectDenial(subjectKey, CODE_DELEGATE_REVOKED,
                        denialMessage(CODE_DELEGATE_REVOKED)));
                continue;
            }

            DelegateRow selected = null;
            String firstFailure = null;
            for (DelegateRow candidate : active) {
                List<VersionRow> versionRows =
                        delegateRepository.findVersion(candidate.delegateKey(), candidate.currentVersion());
                String failure = evaluateVersion(versionRows, purposes, currentEpochs, nowMs);
                if (failure == null) {
                    selected = candidate;
                    break;
                }
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
            if (selected == null) {
                denials.add(new SubjectDenial(subjectKey, firstFailure, denialMessage(firstFailure)));
                continue;
            }

            Map<Purpose, Integer> epochs = new TreeMap<>();
            for (Purpose purpose : purposes) {
                epochs.put(purpose, currentEpochs.get(purpose));
            }
            List<DelegateRecordView> records = new ArrayList<>();
            for (Purpose purpose : purposes) {
                for (ConsentRepository.RecordRow record
                        : consentRepository.findRecords(subjectKey, purpose, currentEpochs.get(purpose))) {
                    records.add(new DelegateRecordView(record.purpose(), record.epoch(),
                            record.recordKey(), record.payload()));
                }
            }
            results.add(new DelegateSubjectResult(subjectKey, selected.delegateKey(),
                    selected.currentVersion(), epochs, records));
        }

        if (!denials.isEmpty()) {
            throw new BatchQueryDeniedException(denials);
        }

        DelegateQueryResponse response = new DelegateQueryResponse(request.requestId(),
                request.agentKey(), purposes, results);
        String joinedPurposes = purposes.stream().map(Purpose::name).collect(Collectors.joining(","));
        delegateRepository.insertSnapshot(request.requestId(), request.agentKey(), joinedPurposes);
        for (DelegateSubjectResult result : results) {
            delegateRepository.insertSnapshotSubject(request.requestId(), result.subjectKey(),
                    result.delegateKey(), result.delegateVersion(), formatEpochs(result.epochs()));
        }
        storeSuccess(request.requestId(), OP_DELEGATE_QUERY, fingerprint, response);
        return response;
    }

    /**
     * 查询委托历史：返回委托当前状态与全部版本，按版本升序。
     */
    @Transactional(readOnly = true)
    public DelegateHistoryResponse history(String delegateKey) {
        DelegateRow delegate = delegateRepository.findDelegate(delegateKey)
                .orElseThrow(() -> ApiException.notFound(CODE_DELEGATE_NOT_FOUND, "委托不存在"));
        Map<Integer, List<VersionRow>> byVersion = delegateRepository.findAllVersions(delegateKey)
                .stream().collect(Collectors.groupingBy(VersionRow::delegateVersion,
                        TreeMap::new, Collectors.toList()));
        List<DelegateVersionView> versions = byVersion.entrySet().stream()
                .map(entry -> toVersionView(entry.getKey(), entry.getValue()))
                .toList();
        return new DelegateHistoryResponse(delegate.delegateKey(), delegate.subjectKey(),
                delegate.agentKey(), delegate.status(), delegate.currentVersion(), versions);
    }

    /**
     * 查询批次快照：成功批次固化的委托版本与授权代次。
     */
    @Transactional(readOnly = true)
    public DelegateQuerySnapshotResponse snapshot(String queryId) {
        SnapshotRow snapshot = delegateRepository.findSnapshot(queryId)
                .orElseThrow(() -> ApiException.notFound(CODE_QUERY_NOT_FOUND, "批次查询快照不存在"));
        List<Purpose> purposes = List.of(snapshot.purposes().split(",")).stream()
                .map(Purpose::valueOf).toList();
        List<SnapshotSubjectView> subjects = new ArrayList<>();
        for (SnapshotSubjectRow row : delegateRepository.findSnapshotSubjects(queryId)) {
            subjects.add(new SnapshotSubjectView(row.subjectKey(), row.delegateKey(),
                    row.delegateVersion(), parseEpochs(row.grantEpochs())));
        }
        return new DelegateQuerySnapshotResponse(snapshot.queryId(), snapshot.agentKey(), purposes, subjects);
    }

    // ---------- 内部辅助 ----------

    /**
     * 评估委托版本是否满足批量查询：覆盖请求用途、代次未迁移、当前时刻处于 UTC 左闭右开区间内。
     *
     * @return 不满足时的稳定原因码；满足时返回 null
     */
    private String evaluateVersion(List<VersionRow> versionRows, List<Purpose> purposes,
                                   Map<Purpose, Integer> currentEpochs, long nowMs) {
        Map<Purpose, Integer> delegated = versionRows.stream()
                .collect(Collectors.toMap(VersionRow::purpose, VersionRow::epoch));
        for (Purpose purpose : purposes) {
            if (!delegated.containsKey(purpose)) {
                return CODE_DELEGATE_PURPOSE_NOT_COVERED;
            }
        }
        for (Purpose purpose : purposes) {
            if (!delegated.get(purpose).equals(currentEpochs.get(purpose))) {
                return CODE_DELEGATE_EPOCH_STALE;
            }
        }
        long validFromMs = versionRows.get(0).validFromMs();
        long validToMs = versionRows.get(0).validToMs();
        if (nowMs < validFromMs) {
            return CODE_DELEGATE_NOT_YET_VALID;
        }
        if (nowMs >= validToMs) {
            return CODE_DELEGATE_EXPIRED;
        }
        return null;
    }

    /**
     * 解析主体各用途当前有效授权代次；无授权返回 404，已撤回返回 410。
     */
    private Map<Purpose, Integer> resolveActiveEpochs(String subjectKey, List<Purpose> purposes) {
        Map<Purpose, Integer> epochs = new TreeMap<>();
        for (Purpose purpose : purposes) {
            ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                    .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
            if (latest.status() == GrantStatus.REVOKED) {
                throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
            }
            epochs.put(purpose, latest.epoch());
        }
        return epochs;
    }

    /**
     * delegateKey 指纹：主体、代理、授权代次、规范化用途、UTC 区间和版本。
     */
    private String delegateFingerprint(String subjectKey, String agentKey, Map<Purpose, Integer> epochs,
                                       List<Purpose> purposes, Instant validFrom, Instant validTo, int version) {
        return "DELEGATE|" + subjectKey + "|" + agentKey
                + "|" + formatEpochs(epochs)
                + "|" + purposes.stream().map(Purpose::name).collect(Collectors.joining(","))
                + "|" + validFrom.toEpochMilli() + "-" + validTo.toEpochMilli()
                + "|v" + version;
    }

    private DelegateResponse toResponse(DelegateRow delegate, int version) {
        List<VersionRow> rows = delegateRepository.findVersion(delegate.delegateKey(), version);
        return new DelegateResponse(delegate.delegateKey(), delegate.subjectKey(), delegate.agentKey(),
                rows.stream().map(VersionRow::purpose).toList(),
                rows.stream().collect(Collectors.toMap(VersionRow::purpose, VersionRow::epoch,
                        (a, b) -> a, TreeMap::new)),
                Instant.ofEpochMilli(rows.get(0).validFromMs()),
                Instant.ofEpochMilli(rows.get(0).validToMs()),
                version, delegate.status());
    }

    private DelegateVersionView toVersionView(int version, List<VersionRow> rows) {
        return new DelegateVersionView(version,
                rows.stream().map(VersionRow::purpose).toList(),
                rows.stream().collect(Collectors.toMap(VersionRow::purpose, VersionRow::epoch,
                        (a, b) -> a, TreeMap::new)),
                Instant.ofEpochMilli(rows.get(0).validFromMs()),
                Instant.ofEpochMilli(rows.get(0).validToMs()));
    }

    private void requireValidWindow(Instant validFrom, Instant validTo) {
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.unprocessableEntity(CODE_DELEGATE_INVALID_WINDOW,
                    "有效期必须满足 validFrom 早于 validTo（UTC 左闭右开）");
        }
    }

    /**
     * 规范化用途集合：去空、去重并按用途名排序。
     */
    private List<Purpose> normalizePurposes(List<Purpose> purposes) {
        if (purposes.stream().anyMatch(p -> p == null)) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "用途集合包含空元素");
        }
        return purposes.stream().distinct()
                .sorted(Comparator.comparing(Purpose::name)).toList();
    }

    private String formatEpochs(Map<Purpose, Integer> epochs) {
        return epochs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Purpose::name)))
                .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    private Map<Purpose, Integer> parseEpochs(String formatted) {
        Map<Purpose, Integer> epochs = new TreeMap<>();
        for (String part : formatted.split(",")) {
            String[] pair = part.split("=");
            epochs.put(Purpose.valueOf(pair[0]), Integer.parseInt(pair[1]));
        }
        return epochs;
    }

    private String denialMessage(String code) {
        return switch (code) {
            case CODE_GRANT_NOT_FOUND -> "主体授权不存在";
            case CODE_CONSENT_REVOKED -> "主体授权已撤回";
            case CODE_DELEGATE_NOT_FOUND -> "主体对该代理不存在委托";
            case CODE_DELEGATE_REVOKED -> "委托已撤销";
            case CODE_DELEGATE_PURPOSE_NOT_COVERED -> "委托未覆盖请求用途";
            case CODE_DELEGATE_EPOCH_STALE -> "委托绑定的授权代次已迁移";
            case CODE_DELEGATE_NOT_YET_VALID -> "委托尚未生效";
            case CODE_DELEGATE_EXPIRED -> "委托已过期";
            default -> "委托校验未通过";
        };
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
