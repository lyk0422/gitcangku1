package com.example.starter.consent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.ClearRequest;
import com.example.starter.consent.dto.ClearResponse;
import com.example.starter.consent.dto.LedgerEntryResponse;
import com.example.starter.consent.dto.PurposeQueryRequest;
import com.example.starter.consent.dto.PurposeQueryResponse;
import com.example.starter.consent.dto.Queries;
import com.example.starter.consent.dto.QueryFailureDetail;
import com.example.starter.consent.dto.QueryRecordItem;
import com.example.starter.consent.dto.QuotaAdjustRequest;
import com.example.starter.consent.dto.QuotaResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权用途账本域服务：为具体用途配置查询额度，受限批量查询按对象数原子核销余额并写只追加账本。
 *
 * <p>核心规则：
 * <ul>
 *   <li>不同用途、不同 epoch 独立计数；撤回 epoch 后查询一律 410 且不扣减；</li>
 *   <li>批量查询先校验全部对象（跨 epoch/不存在）与剩余额度，任一失败不返回部分数据、不扣减、不记账；</li>
 *   <li>额度行在事务内以 FOR UPDATE 锁定，配合条件核销 SQL，并发查询不会超扣；</li>
 *   <li>账本只追加：固化用途、对象规范集合摘要、epoch、扣减前后余额、扣减时额度与完整查询快照，
 *       后续撤回或额度调整不改写已扣减记录；</li>
 *   <li>requestId 同参重放首次完整成功响应快照，异参 409，失败不占键；</li>
 *   <li>所有时间戳统一 UTC、毫秒精度；历史区间左闭右开。</li>
 * </ul>
 */
@Service
public class PurposeLedgerService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_PURPOSE_NOT_DECLARED = "PURPOSE_NOT_DECLARED";
    static final String CODE_QUOTA_INSUFFICIENT = "QUOTA_INSUFFICIENT";
    static final String CODE_QUOTA_LIMIT_BELOW_USED = "QUOTA_LIMIT_BELOW_USED";
    static final String CODE_QUANTITY_MISMATCH = "QUANTITY_MISMATCH";
    static final String CODE_DUPLICATE_OBJECT = "DUPLICATE_OBJECT";
    static final String CODE_OBJECT_CROSS_EPOCH = "OBJECT_CROSS_EPOCH";
    static final String CODE_OBJECT_NOT_FOUND = "OBJECT_NOT_FOUND";
    static final String CODE_GRANT_NOT_REVOKED = "GRANT_NOT_REVOKED";
    static final String CODE_LEDGER_ENTRY_NOT_FOUND = "LEDGER_ENTRY_NOT_FOUND";
    static final String CODE_INVALID_TIME_RANGE = "INVALID_TIME_RANGE";

    private static final String OP_QUOTA_ADJUST = "QUOTA_ADJUST";
    private static final String OP_QUERY = "QUERY";
    private static final String OP_CLEAR = "CLEAR";

    /** 历史/诊断只读查询的安全上限，避免一次性读取全部账本。 */
    private static final int HISTORY_LIMIT = 500;

    private final ConsentRepository consentRepository;
    private final IdempotencySupport idempotency;
    private final ObjectMapper objectMapper;
    private final DomainClock clock;

    public PurposeLedgerService(ConsentRepository consentRepository,
                                IdempotencySupport idempotency,
                                ObjectMapper objectMapper,
                                DomainClock clock) {
        this.consentRepository = consentRepository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 额度声明/调整 ----------

    /**
     * 为指定 epoch 的用途声明或调整查询总额度；只影响后续查询，不改写已扣减账本。
     */
    @Transactional
    public QuotaResponse adjustQuota(QuotaAdjustRequest request) {
        String fingerprint = OP_QUOTA_ADJUST + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch() + "|" + request.quotaLimit();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotency.readSnapshot(replayed.get().responseBody(), QuotaResponse.class);
        }

        ConsentRepository.GrantRow grant = consentRepository
                .lockGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回，不能再调整额度");
        }

        Instant now = clock.nowUtc();
        Optional<ConsentRepository.QuotaRow> existing =
                consentRepository.lockQuota(request.subjectKey(), request.purpose(), request.epoch());
        // 锁内复查并发同 requestId：首次事务可能已在等待期间提交，直接返回其快照
        Optional<IdempotencyRow> concurrentReplay = idempotency.checkReplay(request.requestId(), fingerprint);
        if (concurrentReplay.isPresent()) {
            return idempotency.readSnapshot(concurrentReplay.get().responseBody(), QuotaResponse.class);
        }
        if (existing.isEmpty()) {
            consentRepository.insertQuota(request.subjectKey(), request.purpose(), request.epoch(),
                    request.quotaLimit(), request.requestId(), now);
        } else {
            ConsentRepository.QuotaRow quota = existing.get();
            if (request.quotaLimit() < quota.usedAmount()) {
                long difference = quota.usedAmount() - request.quotaLimit();
                throw ApiException.unprocessable(CODE_QUOTA_LIMIT_BELOW_USED,
                        "调整后总额度不能低于已核销对象数",
                        List.of(QueryFailureDetail.of(QueryFailureCode.QUOTA_INSUFFICIENT,
                                request.quotaLimit(), quota.usedAmount(), difference,
                                "要求总额度 %d 小于实际已用 %d，差额 %d"
                                        .formatted(request.quotaLimit(), quota.usedAmount(), difference))));
            }
            consentRepository.updateQuotaLimit(request.subjectKey(), request.purpose(), request.epoch(),
                    request.quotaLimit(), request.requestId(), now);
        }

        QuotaResponse response = getQuota(request.subjectKey(), request.purpose(), request.epoch());
        idempotency.storeSuccess(request.requestId(), OP_QUOTA_ADJUST, fingerprint, response);
        return response;
    }

    // ---------- 受限批量查询（核销＋记账） ----------

    /**
     * 受限批量查询：先校验全部对象与剩余额度，通过后一次性原子核销并写账本；任一失败整体回滚。
     */
    @Transactional
    public PurposeQueryResponse query(PurposeQueryRequest request) {
        // 集合规范化在事务语义内最先完成：重复键与数量不一致直接拒绝，不占 requestId
        CanonicalSetUtil.CanonicalSet canonical;
        try {
            canonical = CanonicalSetUtil.canonicalize(request.recordKeys(), objectMapper);
        } catch (IllegalArgumentException duplicate) {
            int distinct = canonicalDistinctSize(request.recordKeys());
            throw ApiException.unprocessable(CODE_DUPLICATE_OBJECT, "请求对象键存在重复，无法规范化为集合",
                    List.of(QueryFailureDetail.of(QueryFailureCode.DUPLICATE_OBJECT,
                            (long) distinct, (long) request.recordKeys().size(),
                            (long) (request.recordKeys().size() - distinct),
                            "实际去重对象数 %d，要求提交 %d 个唯一键，重复差额 %d"
                                    .formatted(distinct, request.recordKeys().size(),
                                            request.recordKeys().size() - distinct))));
        }
        int required = canonical.orderedKeys().size();
        if (request.quantity() != required) {
            int difference = Math.abs(request.quantity() - required);
            throw ApiException.unprocessable(CODE_QUANTITY_MISMATCH,
                    "声明数量与去重后对象集合大小不一致",
                    List.of(QueryFailureDetail.of(QueryFailureCode.QUANTITY_MISMATCH,
                            (long) required, request.quantity().longValue(), (long) difference,
                            "实际去重对象数 %d，要求数量 %d，差额 %d"
                                    .formatted(required, request.quantity(), difference))));
        }

        String fingerprint = OP_QUERY + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch() + "|" + request.quantity() + "|" + canonical.canonicalJson();

        // 先锁授权代次行：撤回、查询、额度调整、清除按提交顺序串行裁决
        ConsentRepository.GrantRow grant = consentRepository
                .lockGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            // 撤回后任何用途查询（含同 requestId 重放）均 410，且不得继续扣减
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }

        // 锁内幂等检查：既覆盖普通重放，也覆盖并发同 requestId（首次事务可能恰在等待期间提交）
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            // 同参重放：返回首次完整成功响应快照，读取不改变任何状态
            return idempotency.readSnapshot(replayed.get().responseBody(), PurposeQueryResponse.class);
        }

        // 校验全部对象：逐一定位不存在或属于其他 epoch 的对象，失败原因可定位到对象
        List<QueryFailureDetail> objectFailures = validateObjects(
                request.subjectKey(), request.purpose(), request.epoch(), canonical.orderedKeys());

        // 锁额度行并校验余额
        Optional<ConsentRepository.QuotaRow> quotaRow =
                consentRepository.lockQuota(request.subjectKey(), request.purpose(), request.epoch());
        List<QueryFailureDetail> quotaFailures = new ArrayList<>();
        if (quotaRow.isEmpty()) {
            quotaFailures.add(QueryFailureDetail.of(QueryFailureCode.PURPOSE_NOT_DECLARED,
                    null, (long) required, (long) required,
                    "用途 %s 未在 epoch %d 声明查询额度，要求 %d 个对象"
                            .formatted(request.purpose(), request.epoch(), required)));
        } else {
            long remaining = quotaRow.get().remaining();
            if (remaining < required) {
                quotaFailures.add(QueryFailureDetail.of(QueryFailureCode.QUOTA_INSUFFICIENT,
                        remaining, (long) required, required - remaining,
                        "实际剩余余额 %d，要求 %d，差额 %d"
                                .formatted(remaining, required, required - remaining)));
            }
        }

        // 对象级问题以 422 返回；否则额度/声明问题以 403 返回；任一失败都不扣减、不记账
        if (!objectFailures.isEmpty()) {
            List<QueryFailureDetail> all = new ArrayList<>(objectFailures);
            all.addAll(quotaFailures);
            boolean anyCrossEpoch = objectFailures.stream()
                    .anyMatch(d -> d.reason() == QueryFailureCode.OBJECT_CROSS_EPOCH);
            String code = anyCrossEpoch ? CODE_OBJECT_CROSS_EPOCH : CODE_OBJECT_NOT_FOUND;
            String message = anyCrossEpoch
                    ? "存在不属于当前 epoch 的对象（可能同时含不存在对象）"
                    : "存在当前 epoch 下不存在的对象";
            throw ApiException.unprocessable(code, message, all);
        }
        if (!quotaFailures.isEmpty()) {
            String code = quotaRow.isEmpty() ? CODE_PURPOSE_NOT_DECLARED : CODE_QUOTA_INSUFFICIENT;
            throw ApiException.forbidden(code, code.equals(CODE_PURPOSE_NOT_DECLARED)
                    ? "用途未声明查询额度" : "剩余查询额度不足", quotaFailures);
        }

        // 全部对象与余额校验通过：读取结果（按对象键字典序），随后原子条件核销
        List<ConsentRepository.RecordRow> rows = consentRepository.findRecords(
                request.subjectKey(), request.purpose(), request.epoch(), canonical.orderedKeys());
        Map<String, String> payloadByKey = new LinkedHashMap<>();
        for (ConsentRepository.RecordRow row : rows) {
            payloadByKey.put(row.recordKey(), row.payload());
        }
        List<QueryRecordItem> records = new ArrayList<>(required);
        for (String key : canonical.orderedKeys()) {
            records.add(new QueryRecordItem(key, payloadByKey.get(key)));
        }

        ConsentRepository.QuotaRow quota = quotaRow.orElseThrow(() ->
                ApiException.forbidden(CODE_PURPOSE_NOT_DECLARED, "用途未声明查询额度"));
        long balanceBefore = quota.remaining();
        boolean deducted = consentRepository.deductQuota(
                request.subjectKey(), request.purpose(), request.epoch(), required);
        if (!deducted) {
            // 行锁之外的第二道防线：条件更新失败说明并发下余额已不足
            throw ApiException.forbidden(CODE_QUOTA_INSUFFICIENT, "剩余查询额度不足",
                    List.of(QueryFailureDetail.of(QueryFailureCode.QUOTA_INSUFFICIENT,
                            balanceBefore, (long) required, required - balanceBefore,
                            "并发核销后实际余额不足")));
        }
        long balanceAfter = balanceBefore - required;
        Instant occurredAt = clock.nowUtc();

        PurposeQueryResponse response = new PurposeQueryResponse(request.requestId(), request.subjectKey(),
                request.purpose(), request.epoch(), required, balanceAfter, List.copyOf(records));
        String snapshot = idempotency.writeSnapshot(response);
        String evidenceFingerprint = evidenceFingerprint(request.subjectKey(), request.purpose(),
                request.epoch(), canonical, required, balanceBefore, balanceAfter, quota.quotaLimit(),
                request.requestId(), occurredAt);
        consentRepository.insertLedger(new ConsentRepository.LedgerRow(
                0L, request.requestId(), request.subjectKey(), request.purpose(), request.epoch(),
                canonical.digest(), required, balanceBefore, balanceAfter, quota.quotaLimit(),
                snapshot, evidenceFingerprint, occurredAt));
        idempotency.storeSuccess(request.requestId(), OP_QUERY, fingerprint, response);
        return response;
    }

    // ---------- 合规清除 ----------

    /**
     * 清除：仅撤回后的 epoch 可清除；标记记录清除时间，不物理删除、不改写账本与授权代次。
     */
    @Transactional
    public ClearResponse clear(ClearRequest request) {
        String fingerprint = OP_CLEAR + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotency.readSnapshot(replayed.get().responseBody(), ClearResponse.class);
        }

        ConsentRepository.GrantRow grant = consentRepository
                .lockGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        // 锁内复查并发同 requestId：首次清除可能恰在等待期间提交，返回其快照且不重复打标记
        Optional<IdempotencyRow> concurrentReplay = idempotency.checkReplay(request.requestId(), fingerprint);
        if (concurrentReplay.isPresent()) {
            return idempotency.readSnapshot(concurrentReplay.get().responseBody(), ClearResponse.class);
        }
        if (grant.status() != GrantStatus.REVOKED) {
            throw ApiException.conflict(CODE_GRANT_NOT_REVOKED, "仅已撤回的授权代次可以清除");
        }

        int cleared = consentRepository.markRecordsCleared(request.subjectKey(), request.purpose(),
                request.epoch(), clock.nowUtc());
        ClearResponse response = new ClearResponse(request.subjectKey(), request.purpose(),
                request.epoch(), cleared);
        idempotency.storeSuccess(request.requestId(), OP_CLEAR, fingerprint, response);
        return response;
    }

    // ---------- 只读：明细、历史、诊断（读取不改变状态） ----------

    /**
     * 查询某 epoch 某用途的当前额度视图；未声明时 declared=false，额度字段为 null。
     */
    @Transactional(readOnly = true)
    public QuotaResponse getQuota(String subjectKey, Purpose purpose, int epoch) {
        return consentRepository.findQuota(subjectKey, purpose, epoch)
                .map(q -> new QuotaResponse(q.subjectKey(), q.purpose(), q.epoch(), true,
                        q.quotaLimit(), q.usedAmount(), q.remaining()))
                .orElseGet(() -> new QuotaResponse(subjectKey, purpose, epoch, false, null, null, null));
    }

    /**
     * 按 epoch 列账本明细，按全局顺序号稳定升序；账本只追加，读取不改变状态。
     */
    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> listEntries(String subjectKey, Purpose purpose, int epoch) {
        return consentRepository.listLedgerByScope(subjectKey, purpose, epoch).stream()
                .map(PurposeLedgerService::toEntryResponse)
                .toList();
    }

    /**
     * 按顺序号查看单条历史证据；字段、时间精度与 null 含义保持稳定。
     */
    @Transactional(readOnly = true)
    public LedgerEntryResponse getEntry(long ledgerSeq) {
        return consentRepository.findLedgerBySeq(ledgerSeq)
                .map(PurposeLedgerService::toEntryResponse)
                .orElseThrow(() -> ApiException.notFound(CODE_LEDGER_ENTRY_NOT_FOUND, "账本条目不存在"));
    }

    /**
     * 历史区间查询：UTC 口径，区间左闭右开 [fromUtc, toUtc)，按 (发生时间, 顺序号) 稳定排序。
     *
     * @param fromUtc ISO-8601 下界（含），null 表示不设下界
     * @param toUtc   ISO-8601 上界（不含），null 表示不设上界
     */
    @Transactional(readOnly = true)
    public Queries.HistoryWindow history(String subjectKey, Purpose purpose, Integer epoch,
                                         String fromUtc, String toUtc) {
        Instant from = parseInstant(fromUtc, "from");
        Instant to = parseInstant(toUtc, "to");
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest(CODE_INVALID_TIME_RANGE, "历史区间下界必须早于上界（左闭右开）");
        }
        List<LedgerEntryResponse> entries = consentRepository
                .listLedgerHistory(subjectKey, purpose, epoch, from, to, HISTORY_LIMIT).stream()
                .map(PurposeLedgerService::toEntryResponse)
                .toList();
        return new Queries.HistoryWindow(fromUtc, toUtc, entries.size(), entries);
    }

    /**
     * 诊断视图：聚合授权状态、额度、记录清除数量与账本条数；纯读取。
     */
    @Transactional(readOnly = true)
    public Queries.Diagnostics diagnostics(String subjectKey, Purpose purpose) {
        List<ConsentRepository.GrantRow> grants = consentRepository.listGrants(subjectKey, purpose);
        List<Queries.EpochDiagnostics> epochs = grants.stream()
                .map(grant -> {
                    QuotaResponse quota = getQuota(subjectKey, purpose, grant.epoch());
                    long totalRecords = consentRepository.countRecords(subjectKey, purpose, grant.epoch());
                    long clearedRecords = consentRepository.countClearedRecords(subjectKey, purpose, grant.epoch());
                    long ledgerEntries = consentRepository.listLedgerByScope(
                            subjectKey, purpose, grant.epoch()).size();
                    return new Queries.EpochDiagnostics(grant.epoch(), grant.status().name(), quota,
                            totalRecords, clearedRecords, ledgerEntries);
                })
                .toList();
        return new Queries.Diagnostics(subjectKey, purpose, epochs,
                consentRepository.countLedger(subjectKey, purpose));
    }

    // ---------- 内部辅助 ----------

    private List<QueryFailureDetail> validateObjects(String subjectKey, Purpose purpose,
                                                     int epoch, List<String> orderedKeys) {
        List<ConsentRepository.RecordRow> present =
                consentRepository.findRecords(subjectKey, purpose, epoch, orderedKeys);
        java.util.Set<String> presentKeys = new java.util.HashSet<>();
        for (ConsentRepository.RecordRow row : present) {
            presentKeys.add(row.recordKey());
        }
        List<QueryFailureDetail> failures = new ArrayList<>();
        for (String key : orderedKeys) {
            if (presentKeys.contains(key)) {
                continue;
            }
            Optional<ConsentRepository.RecordRow> otherEpoch =
                    consentRepository.findRecordAcrossEpochs(subjectKey, purpose, key);
            if (otherEpoch.isPresent()) {
                failures.add(QueryFailureDetail.ofObject(QueryFailureCode.OBJECT_CROSS_EPOCH, key,
                        (long) otherEpoch.get().epoch(), (long) epoch,
                        "对象 %s 实际属于 epoch %d，要求当前 epoch %d"
                                .formatted(key, otherEpoch.get().epoch(), epoch)));
            } else {
                failures.add(QueryFailureDetail.ofObject(QueryFailureCode.OBJECT_NOT_FOUND, key,
                        null, (long) epoch,
                        "对象 %s 在 epoch %d 不存在".formatted(key, epoch)));
            }
        }
        return failures;
    }

    /**
     * 证据指纹：覆盖操作者（主体）、操作类型、规范化集合原文与摘要、epoch、对象数、
     * 扣减前后余额、扣减时额度、requestId 与 UTC 发生时间等全部结果相关字段。
     * 输入以固定字段顺序序列化为 JSON 后做 SHA-256，集合不使用裸分隔符拼接。
     */
    private String evidenceFingerprint(String subjectKey, Purpose purpose, int epoch,
                                       CanonicalSetUtil.CanonicalSet canonical, int objectCount,
                                       long balanceBefore, long balanceAfter, long quotaLimit,
                                       String requestId, Instant occurredAt) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("operator", subjectKey);
        evidence.put("operation", OP_QUERY);
        evidence.put("purpose", purpose.name());
        evidence.put("epoch", epoch);
        evidence.put("canonicalSet", canonical.canonicalJson());
        evidence.put("canonicalSetDigest", canonical.digest());
        evidence.put("objectCount", objectCount);
        evidence.put("balanceBefore", balanceBefore);
        evidence.put("balanceAfter", balanceAfter);
        evidence.put("quotaLimit", quotaLimit);
        evidence.put("requestId", requestId);
        evidence.put("occurredAtUtcMillis", occurredAt.toEpochMilli());
        String json;
        try {
            json = objectMapper.writeValueAsString(evidence);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("证据指纹序列化失败", e);
        }
        return CanonicalSetUtil.sha256Hex(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int canonicalDistinctSize(List<String> keys) {
        return new java.util.HashSet<>(keys).size();
    }

    private static LedgerEntryResponse toEntryResponse(ConsentRepository.LedgerRow row) {
        return new LedgerEntryResponse(row.ledgerSeq(), row.requestId(), row.subjectKey(), row.purpose(),
                row.epoch(), row.canonicalSetDigest(), row.objectCount(), row.balanceBefore(),
                row.balanceAfter(), row.quotaLimit(), row.querySnapshot(), row.fingerprint(),
                row.occurredAtUtc().toString());
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(CODE_INVALID_TIME_RANGE,
                    "时间参数 %s 不是合法 ISO-8601 UTC 时间: %s".formatted(field, value));
        }
    }
}
