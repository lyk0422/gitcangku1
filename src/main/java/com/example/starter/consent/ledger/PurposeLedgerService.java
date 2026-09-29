package com.example.starter.consent.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ConsentRepository;
import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.Purpose;
import com.example.starter.consent.ledger.LedgerIdempotencyRepository.Row;
import com.example.starter.consent.ledger.LedgerRepository.GrantStatusRow;
import com.example.starter.consent.ledger.LedgerRepository.QuotaRow;
import com.example.starter.consent.ledger.LedgerRepository.RecordData;
import com.example.starter.consent.ledger.dto.DiagnosticResponse;
import com.example.starter.consent.ledger.dto.LedgerEntryResponse;
import com.example.starter.consent.ledger.dto.LedgerHistoryResponse;
import com.example.starter.consent.ledger.dto.QuotaAdjustRequest;
import com.example.starter.consent.ledger.dto.QuotaClearRequest;
import com.example.starter.consent.ledger.dto.QuotaResponse;
import com.example.starter.consent.ledger.dto.RestrictedQueryRequest;
import com.example.starter.consent.ledger.dto.RestrictedQueryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权用途账本服务：额度调整/清除、受限批量查询核销与账本读取。
 *
 * <p>核心规则：
 * <ul>
 *   <li>不同用途、不同代次独立计数；撤回后查询一律 410 且不再扣减；</li>
 *   <li>受限查询先一次性校验全部对象（存在性、所属代次一致、数量一致）与剩余额度，
 *       任一失败整体回滚，不返回部分数据、不写账；</li>
 *   <li>幂等占键行 + 授权行 FOR UPDATE + 额度行 FOR UPDATE 在同一事务内按固定顺序加锁，
 *       额度、查询、撤回、清除按提交顺序裁决，并发查询不超扣；</li>
 *   <li>账本仅追加，seq 在事务内由 ledger_seq 分配，按提交顺序单调递增；</li>
 *   <li>requestId 同参重放首次完整成功快照，异参 409，失败回滚不占键；</li>
 *   <li>UTC 微秒精度为统一时间口径，历史区间左闭右开。</li>
 * </ul>
 */
@Service
public class PurposeLedgerService {

    static final String OP_QUOTA_ADJUST = "QUOTA_ADJUST";
    static final String OP_RESTRICTED_QUERY = "RESTRICTED_QUERY";
    static final String OP_QUOTA_CLEAR = "QUOTA_CLEAR";

    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_REQUEST_IN_PROGRESS = "REQUEST_IN_PROGRESS";
    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_PURPOSE_NOT_DECLARED = "PURPOSE_NOT_DECLARED";
    static final String CODE_QUOTA_INSUFFICIENT = "QUOTA_INSUFFICIENT";
    static final String CODE_QUOTA_CLEARED = "QUOTA_CLEARED";
    static final String CODE_OBJECT_NOT_FOUND = "OBJECT_NOT_FOUND";
    static final String CODE_OBJECT_EPOCH_MISMATCH = "OBJECT_EPOCH_MISMATCH";
    static final String CODE_QUANTITY_MISMATCH = "QUANTITY_MISMATCH";
    static final String CODE_LEDGER_ENTRY_NOT_FOUND = "LEDGER_ENTRY_NOT_FOUND";
    static final String CODE_INVALID_TIME_RANGE = "INVALID_TIME_RANGE";

    /** 并发同 requestId 等待先到事务提交的最长时间（毫秒），超时返回 409。 */
    private static final long PENDING_WAIT_MILLIS = 30_000L;
    private static final long PENDING_WAIT_SLEEP_MILLIS = 20L;

    private final LedgerRepository ledgerRepository;
    private final LedgerIdempotencyRepository idempotencyRepository;
    private final ConsentRepository consentRepository;
    private final BusinessClock clock;
    private final ObjectMapper objectMapper;

    public PurposeLedgerService(LedgerRepository ledgerRepository,
                                LedgerIdempotencyRepository idempotencyRepository,
                                ConsentRepository consentRepository,
                                BusinessClock clock,
                                ObjectMapper objectMapper) {
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.consentRepository = consentRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 额度调整：首次声明或对 ACTIVE 额度行上调/下调；CLEARED 为终态不可再调整。
     */
    @Transactional
    public QuotaResponse adjustQuota(QuotaAdjustRequest request) {
        String purpose = requireValidPurpose(request.purpose());
        String paramsFingerprint = fingerprint(Fingerprints.fields(
                "operator", request.subjectKey(),
                "operation", OP_QUOTA_ADJUST,
                "purpose", purpose,
                "epoch", request.epoch(),
                "quota", request.quota()));
        Optional<Row> replayed = acquirePending(request.requestId(), OP_QUOTA_ADJUST, paramsFingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), QuotaResponse.class);
        }

        requireGrantActive(request.subjectKey(), purpose, request.epoch());
        Instant now = clock.now();

        QuotaRow existing = lockQuota(request.subjectKey(), purpose, request.epoch());
        Long balanceBefore;
        if (existing == null) {
            balanceBefore = null;
            try {
                // seq 先占位 -1，账本条目追加后再更新额度行；插入与账本在同一事务
                ledgerRepository.insertQuota(request.subjectKey(), purpose, request.epoch(),
                        request.quota(), -1L, now);
            } catch (DuplicateKeyException concurrent) {
                // 并发首次声明：以提交顺序裁决，本事务退化为对已提交额度的一次调整
                existing = requireLockedQuota(request.subjectKey(), purpose, request.epoch());
                if (existing.status() == QuotaStatus.CLEARED) {
                    throw quotaCleared(purpose, request.epoch());
                }
                balanceBefore = existing.balance();
            }
        } else {
            if (existing.status() == QuotaStatus.CLEARED) {
                throw quotaCleared(purpose, request.epoch());
            }
            balanceBefore = existing.balance();
        }

        long seq = ledgerRepository.nextSeq();
        String entryFingerprint = entryFingerprint(seq, request.requestId(), request.subjectKey(),
                LedgerEntryType.QUOTA_ADJUST.name(), purpose, request.epoch(),
                QuotaStatus.ACTIVE.name(), null, null, balanceBefore, request.quota(), now, null);
        ledgerRepository.insertEntry(seq, LedgerEntryType.QUOTA_ADJUST, request.requestId(),
                request.subjectKey(), purpose, request.epoch(), QuotaStatus.ACTIVE,
                null, null, balanceBefore, request.quota(), entryFingerprint, null, now);
        int updated = ledgerRepository.updateBalance(request.subjectKey(), purpose, request.epoch(),
                request.quota(), seq, now);
        if (updated != 1) {
            throw new IllegalStateException("额度行更新行数异常: " + updated);
        }

        QuotaResponse response = new QuotaResponse(request.requestId(), request.subjectKey(), purpose,
                request.epoch(), OP_QUOTA_ADJUST, QuotaStatus.ACTIVE, balanceBefore, request.quota(), seq, now);
        completePending(request.requestId(), response);
        return response;
    }

    /**
     * 额度清除：余额置 null、状态置 CLEARED 并追加账本；已扣减历史不被改写。
     */
    @Transactional
    public QuotaResponse clearQuota(QuotaClearRequest request) {
        String purpose = requireValidPurpose(request.purpose());
        String paramsFingerprint = fingerprint(Fingerprints.fields(
                "operator", request.subjectKey(),
                "operation", OP_QUOTA_CLEAR,
                "purpose", purpose,
                "epoch", request.epoch()));
        Optional<Row> replayed = acquirePending(request.requestId(), OP_QUOTA_CLEAR, paramsFingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), QuotaResponse.class);
        }

        GrantStatusRow grant = ledgerRepository.findGrantStatus(request.subjectKey(), purpose, request.epoch());
        if (!grant.exists()) {
            throw LedgerApiException.of(HttpStatus.NOT_FOUND, CODE_GRANT_NOT_FOUND, "授权代次不存在");
        }
        Instant now = clock.now();

        QuotaRow existing = lockQuota(request.subjectKey(), purpose, request.epoch());
        if (existing == null || existing.status() == QuotaStatus.CLEARED) {
            // 未声明或已清除：不写账，事务回滚后 requestId 不被占用
            throw LedgerApiException.of(HttpStatus.FORBIDDEN, CODE_PURPOSE_NOT_DECLARED,
                    "用途未声明或额度已清除: " + purpose);
        }
        Long balanceBefore = existing.balance();

        long seq = ledgerRepository.nextSeq();
        String entryFingerprint = entryFingerprint(seq, request.requestId(), request.subjectKey(),
                LedgerEntryType.CLEAR.name(), purpose, request.epoch(),
                QuotaStatus.CLEARED.name(), null, null, balanceBefore, null, now, null);
        ledgerRepository.insertEntry(seq, LedgerEntryType.CLEAR, request.requestId(),
                request.subjectKey(), purpose, request.epoch(), QuotaStatus.CLEARED,
                null, null, balanceBefore, null, entryFingerprint, null, now);
        int cleared = ledgerRepository.clearQuota(request.subjectKey(), purpose, request.epoch(), seq, now);
        if (cleared != 1) {
            throw new IllegalStateException("额度清除行数异常: " + cleared);
        }

        QuotaResponse response = new QuotaResponse(request.requestId(), request.subjectKey(), purpose,
                request.epoch(), OP_QUOTA_CLEAR, QuotaStatus.CLEARED, balanceBefore, null, seq, now);
        completePending(request.requestId(), response);
        return response;
    }

    /**
     * 受限批量查询：全部对象与剩余额度校验通过后，原子核销（扣减余额 + 追加账本 + 固化快照）。
     */
    @Transactional
    public RestrictedQueryResponse restrictedQuery(RestrictedQueryRequest request) {
        String purpose = requireValidPurpose(request.purpose());

        // 规范化对象集合：去重；同一 recordKey 出现不同声明数量视为数量不一致（422），不做任何写入
        LinkedHashMap<String, Long> normalized = new LinkedHashMap<>();
        for (RestrictedQueryRequest.ObjectSpec object : request.objects()) {
            Long previous = normalized.put(object.recordKey(), object.count());
            if (previous != null && !previous.equals(object.count())) {
                throw LedgerApiException.quantity(HttpStatus.UNPROCESSABLE_ENTITY, CODE_QUANTITY_MISMATCH,
                        "同一对象声明的数量不一致: " + object.recordKey(),
                        previous, object.count(),
                        "recordKey=" + object.recordKey());
            }
        }
        List<String> recordKeys = normalized.keySet().stream().sorted().toList();
        long required = normalized.values().stream().mapToLong(Long::longValue).sum();

        // 规范化对象规范集合（含数量），按 recordKey 排序后输出稳定 JSON 令牌
        List<String> objectTokens = new ArrayList<>();
        for (String recordKey : recordKeys) {
            objectTokens.add(canonicalJson(Fingerprints.fields(
                    "count", normalized.get(recordKey), "recordKey", recordKey)));
        }
        String objectDigest = sha256(canonicalJson(objectTokens));

        String paramsFingerprint = fingerprint(Fingerprints.fields(
                "operator", request.subjectKey(),
                "operation", OP_RESTRICTED_QUERY,
                "purpose", purpose,
                "epoch", request.epoch(),
                "objectsDigest", objectDigest));

        Optional<Row> replayed = acquirePending(request.requestId(), OP_RESTRICTED_QUERY, paramsFingerprint);
        if (replayed.isPresent()) {
            RestrictedQueryResponse snapshot =
                    readSnapshot(replayed.get().responseBody(), RestrictedQueryResponse.class);
            // 撤回后任何查询（含重放）一律 410，且重放读取快照不再扣减
            requireGrantActive(request.subjectKey(), purpose, snapshot.epoch());
            return snapshot;
        }

        // 撤回判定先于额度判定，且授权行当前读加锁：撤回 epoch 后任何用途查询均 410
        requireGrantActive(request.subjectKey(), purpose, request.epoch());

        // 行锁串行化同一额度的并发核销；以下全部校验通过前不产生任何业务写入
        QuotaRow quota = lockQuota(request.subjectKey(), purpose, request.epoch());
        if (quota == null || quota.status() == QuotaStatus.CLEARED) {
            throw LedgerApiException.of(HttpStatus.FORBIDDEN, CODE_PURPOSE_NOT_DECLARED,
                    "用途未声明查询额度: " + purpose);
        }

        List<RecordData> records = ledgerRepository.findRecords(
                request.subjectKey(), purpose, recordKeys);
        Map<String, List<RecordData>> byKey = new TreeMap<>();
        for (RecordData record : records) {
            byKey.computeIfAbsent(record.recordKey(), key -> new ArrayList<>()).add(record);
        }
        Map<String, RecordData> targetEpochRecords = new TreeMap<>();
        for (String recordKey : recordKeys) {
            List<RecordData> candidates = byKey.get(recordKey);
            if (candidates == null || candidates.isEmpty()) {
                throw LedgerApiException.quantity(HttpStatus.UNPROCESSABLE_ENTITY, CODE_OBJECT_NOT_FOUND,
                        "查询对象在该用途下不存在: " + recordKey, 0L, 1L,
                        "recordKey=" + recordKey);
            }
            RecordData target = candidates.stream()
                    .filter(row -> row.epoch() == request.epoch())
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                // 对象存在但仅属于其他代次（重新授权复用键不算冲突；目标代次缺失才算跨代次）
                RecordData other = candidates.get(0);
                throw LedgerApiException.quantity(HttpStatus.UNPROCESSABLE_ENTITY, CODE_OBJECT_EPOCH_MISMATCH,
                        "查询对象属于其他授权代次", (long) other.epoch(), (long) request.epoch(),
                        "recordKey=" + recordKey);
            }
            targetEpochRecords.put(recordKey, target);
            long declaredCount = normalized.get(recordKey);
            long actualCount = actualObjectCount(target.payload());
            if (actualCount != declaredCount) {
                // 声明数量与记录实际数量不一致：返回实际值、要求值与差额（要求 - 实际）
                throw LedgerApiException.quantity(HttpStatus.UNPROCESSABLE_ENTITY, CODE_QUANTITY_MISMATCH,
                        "对象数量与记录实际数量不一致", actualCount, declaredCount,
                        "recordKey=" + recordKey);
            }
        }

        long balanceBefore = quota.balance() == null ? 0L : quota.balance();
        if (balanceBefore < required) {
            throw LedgerApiException.quantity(HttpStatus.FORBIDDEN, CODE_QUOTA_INSUFFICIENT,
                    "剩余额度不足", balanceBefore, required,
                    "purpose=" + purpose + ",epoch=" + request.epoch());
        }
        long balanceAfter = balanceBefore - required;

        // 全部最终状态校验通过，统一提交：序号分配 -> 账本追加（含快照）-> 余额扣减 -> 幂等补齐
        Instant now = clock.now();
        List<RestrictedQueryResponse.Item> results = recordKeys.stream()
                .map(key -> new RestrictedQueryResponse.Item(key, targetEpochRecords.get(key).payload()))
                .toList();
        long seq = ledgerRepository.nextSeq();
        List<Map<String, Object>> canonicalResults = new ArrayList<>();
        for (RestrictedQueryResponse.Item item : results) {
            canonicalResults.add(Fingerprints.fields("payload", item.payload(), "recordKey", item.recordKey()));
        }
        String entryFingerprint = entryFingerprint(seq, request.requestId(), request.subjectKey(),
                LedgerEntryType.QUERY.name(), purpose, request.epoch(),
                QuotaStatus.ACTIVE.name(), recordKeys.size(), objectDigest,
                balanceBefore, balanceAfter, now, canonicalResults);

        RestrictedQueryResponse response = new RestrictedQueryResponse(
                request.requestId(), request.subjectKey(), purpose, request.epoch(),
                recordKeys.size(), objectDigest, balanceBefore, balanceAfter, seq, now, results);

        ledgerRepository.insertEntry(seq, LedgerEntryType.QUERY, request.requestId(),
                request.subjectKey(), purpose, request.epoch(), QuotaStatus.ACTIVE,
                recordKeys.size(), objectDigest, balanceBefore, balanceAfter,
                entryFingerprint, writeSnapshot(response), now);
        int updated = ledgerRepository.updateBalance(request.subjectKey(), purpose, request.epoch(),
                balanceAfter, seq, now);
        if (updated != 1) {
            throw new IllegalStateException("额度扣减行数异常: " + updated);
        }
        completePending(request.requestId(), response);
        return response;
    }

    /**
     * 账本明细：纯读取，不改变状态；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public LedgerEntryResponse detail(long seq) {
        return ledgerRepository.findEntry(seq)
                .orElseThrow(() -> LedgerApiException.of(HttpStatus.NOT_FOUND,
                        CODE_LEDGER_ENTRY_NOT_FOUND, "账本条目不存在: seq=" + seq));
    }

    /**
     * 账本历史：UTC 区间左闭右开 {@code [from, to)}，按 seq 升序；纯读取。
     */
    @Transactional(readOnly = true)
    public LedgerHistoryResponse history(String subjectKey, String purpose, Integer epoch,
                                         Instant from, Instant to) {
        String normalizedPurpose = null;
        if (purpose != null) {
            normalizedPurpose = requireValidPurpose(purpose);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw LedgerApiException.of(HttpStatus.BAD_REQUEST, CODE_INVALID_TIME_RANGE,
                    "时间区间必须满足 from < to（左闭右开）");
        }
        List<LedgerEntryResponse> entries =
                ledgerRepository.listEntries(subjectKey, normalizedPurpose, epoch, from, to);
        return new LedgerHistoryResponse(subjectKey, normalizedPurpose, from, to, entries.size(), entries);
    }

    /**
     * 诊断：授权、额度与账本累计信息；纯读取。
     */
    @Transactional(readOnly = true)
    public DiagnosticResponse diagnose(String subjectKey, String purpose, int epoch) {
        String normalizedPurpose = requireValidPurpose(purpose);
        GrantStatusRow grant = ledgerRepository.findGrantStatus(subjectKey, normalizedPurpose, epoch);
        Optional<QuotaRow> quota =
                ledgerRepository.findQuota(subjectKey, normalizedPurpose, epoch, false);
        LedgerRepository.LedgerStats stats =
                ledgerRepository.stats(subjectKey, normalizedPurpose, epoch);
        Instant lastOccurredAt = stats.lastSeq() == null ? null
                : ledgerRepository.findEntry(stats.lastSeq()).map(LedgerEntryResponse::occurredAtUtc).orElse(null);
        return new DiagnosticResponse(
                subjectKey, normalizedPurpose, epoch,
                grant.exists(), grant.status(),
                quota.isPresent(),
                quota.map(QuotaRow::status).orElse(null),
                quota.map(QuotaRow::balance).orElse(null),
                (int) stats.totalCount(),
                (int) stats.queryCount(),
                stats.totalDeducted(),
                stats.lastSeq(),
                lastOccurredAt);
    }

    /**
     * 解析并校验用途名，非法用途抛出 400。
     */
    static String requireValidPurpose(String purpose) {
        try {
            Purpose.valueOf(purpose);
            return purpose;
        } catch (IllegalArgumentException ex) {
            throw LedgerApiException.of(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "未知用途: " + purpose);
        }
    }

    /**
     * 授权状态校验：当前读加行锁，与撤回按提交顺序互斥；不存在 404，已撤回 410。
     */
    private void requireGrantActive(String subjectKey, String purpose, int epoch) {
        com.example.starter.consent.ConsentRepository.GrantRow grant = consentRepository
                .findGrantForUpdate(subjectKey, Purpose.valueOf(purpose), epoch)
                .orElseThrow(() -> LedgerApiException.of(HttpStatus.NOT_FOUND,
                        CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw LedgerApiException.of(HttpStatus.GONE, CODE_CONSENT_REVOKED,
                    "授权已撤回，禁止用途查询与扣减");
        }
    }

    private QuotaRow lockQuota(String subjectKey, String purpose, int epoch) {
        return ledgerRepository.findQuota(subjectKey, purpose, epoch, true).orElse(null);
    }

    private QuotaRow requireLockedQuota(String subjectKey, String purpose, int epoch) {
        return ledgerRepository.findQuota(subjectKey, purpose, epoch, true)
                .orElseThrow(() -> new IllegalStateException("并发声明后额度行仍不存在"));
    }

    private LedgerApiException quotaCleared(String purpose, int epoch) {
        return LedgerApiException.of(HttpStatus.CONFLICT, CODE_QUOTA_CLEARED,
                "用途额度已清除，不可再调整: " + purpose + "#" + epoch);
    }

    /**
     * 事务内占用 requestId。
     *
     * <p>返回空表示占键成功，调用方继续执行业务；返回非空表示存在已成功的同 requestId，
     * 调用方直接重放其快照。参数指纹不一致返回 409；先到事务仍在处理则等待其提交或回滚，
     * 超时返回 409。业务失败随事务回滚，PENDING 行自动释放（失败不占键）。
     */
    private Optional<Row> acquirePending(String requestId, String operation, String paramsFingerprint) {
        Optional<Row> existing = idempotencyRepository.find(requestId);
        if (existing.isPresent() && existing.get().succeeded()) {
            return replayOrConflict(existing.get(), paramsFingerprint);
        }
        long deadline = System.currentTimeMillis() + PENDING_WAIT_MILLIS;
        while (true) {
            try {
                idempotencyRepository.insertPending(requestId, operation, paramsFingerprint);
                return Optional.empty();
            } catch (DuplicateKeyException blocked) {
                Row row = idempotencyRepository.find(requestId).orElse(null);
                if (row == null) {
                    // 先到事务已回滚：占位释放，重试占键
                    continue;
                }
                if (row.succeeded()) {
                    return replayOrConflict(row, paramsFingerprint);
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw LedgerApiException.of(HttpStatus.CONFLICT, CODE_REQUEST_IN_PROGRESS,
                            "同一 requestId 的请求仍在处理中");
                }
                try {
                    Thread.sleep(PENDING_WAIT_SLEEP_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待同 requestId 处理时被中断", interrupted);
                }
            }
        }
    }

    private Optional<Row> replayOrConflict(Row row, String paramsFingerprint) {
        if (!row.paramsFingerprint().equals(paramsFingerprint)) {
            throw LedgerApiException.of(HttpStatus.CONFLICT, CODE_REQUEST_ID_CONFLICT,
                    "同一 requestId 参数不一致");
        }
        return Optional.of(row);
    }

    /**
     * 同事务把 PENDING 占位补齐为 SUCCEEDED 并固化首次完整成功响应快照。
     */
    private void completePending(String requestId, Object response) {
        idempotencyRepository.complete(requestId, writeSnapshot(response));
    }

    /**
     * 记录承载的实际对象数量（单位：个）：payload 为十进制正整数时以其值为准，
     * 非数值合成内容视为 1 个对象。核销与校验均以此实际值为准。
     */
    private long actualObjectCount(String payload) {
        try {
            long parsed = Long.parseLong(payload);
            return parsed > 0 ? parsed : 1L;
        } catch (NumberFormatException ex) {
            return 1L;
        }
    }

    private String entryFingerprint(long seq, String requestId, String subjectKey, String operation,
                                    String purpose, int epoch, String status,
                                    Integer objectCount, String objectDigest,
                                    Long balanceBefore, Long balanceAfter, Instant occurredAt,
                                    List<Map<String, Object>> results) {
        Map<String, Object> fields = Fingerprints.fields(
                "seq", seq,
                "requestId", requestId,
                "operator", subjectKey,
                "operation", operation,
                "purpose", purpose,
                "epoch", epoch,
                "status", status,
                "objectCount", objectCount,
                "objectDigest", objectDigest,
                "balanceBefore", balanceBefore,
                "balanceAfter", balanceAfter,
                "occurredAtUtc", occurredAt.toString());
        if (results != null) {
            fields.put("results", results);
        }
        return fingerprint(fields);
    }

    private String fingerprint(Map<String, Object> fields) {
        return sha256(canonicalJson(new TreeMap<>(fields)));
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规范化序列化失败", e);
        }
    }

    private String sha256(String input) {
        return Fingerprints.sha256(input);
    }

    private String writeSnapshot(Object response) {
        return canonicalJson(response);
    }

    private <T> T readSnapshot(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照反序列化失败", e);
        }
    }
}
