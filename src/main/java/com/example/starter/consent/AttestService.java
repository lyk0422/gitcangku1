package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.AttestationRepository.AttestationRow;
import com.example.starter.consent.BatchQueryRepository.BatchRow;
import com.example.starter.consent.BatchQueryRepository.BlockRow;
import com.example.starter.consent.BatchQueryRepository.SnapshotRow;
import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.ConsentRepository.RecordRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.RecipientRepository.RecipientRow;
import com.example.starter.consent.dto.AttestRevokeRequest;
import com.example.starter.consent.dto.AttestSubmitRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.BatchBlockDetail;
import com.example.starter.consent.dto.BatchDetailResponse;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.RecipientDisableRequest;
import com.example.starter.consent.dto.RecipientRegisterRequest;
import com.example.starter.consent.dto.RecipientResponse;
import com.example.starter.consent.dto.SnapshotItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 接收方证明域服务：接收方登记/禁用、证明提交（版本化续签）与撤销、
 * 带证明门禁的批量数据查询及不可改写快照。
 *
 * <p>并发裁决：续签、撤销、用途迁移（撤回后重新授权产生新代次）、批次查询与接收方禁用均在
 * 同一事务内按固定顺序对接收方行、授权当前代次行与证明作用域/版本行加行锁，按事务提交顺序裁决。
 *
 * <p>幂等：requestId 全局唯一；证明的指纹（attestKey）含接收方、用途代次、到期与声明摘要，
 * 同键同参重放返回首次完整响应，参数不一致 409；失败（含批次门禁 403）不写入幂等记录、不占键。
 */
@Service
public class AttestService {

    static final String CODE_RECIPIENT_NOT_FOUND = "RECIPIENT_NOT_FOUND";
    static final String CODE_RECIPIENT_ALREADY_EXISTS = "RECIPIENT_ALREADY_EXISTS";
    static final String CODE_RECIPIENT_ALREADY_DISABLED = "RECIPIENT_ALREADY_DISABLED";
    static final String CODE_BATCH_FORBIDDEN = "BATCH_QUERY_FORBIDDEN";
    static final String CODE_BATCH_NOT_FOUND = "BATCH_NOT_FOUND";
    static final String CODE_ATTESTATION_EXPIRED = "ATTESTATION_EXPIRED";
    static final String CODE_ATTESTATION_NOT_FOUND = "ATTESTATION_NOT_FOUND";
    static final String CODE_ATTESTATION_ALREADY_REVOKED = "ATTESTATION_ALREADY_REVOKED";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    static final String REASON_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String REASON_GRANT_REVOKED = "GRANT_REVOKED";
    static final String REASON_ATTESTATION_MISSING = "ATTESTATION_MISSING";
    static final String REASON_ATTESTATION_EXPIRED = "ATTESTATION_EXPIRED";
    static final String REASON_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String REASON_RECIPIENT_DISABLED = "RECIPIENT_DISABLED";

    private static final String OP_RECIPIENT_REGISTER = "RECIPIENT_REGISTER";
    private static final String OP_RECIPIENT_DISABLE = "RECIPIENT_DISABLE";
    private static final String OP_ATTEST = "ATTEST";
    private static final String OP_ATTEST_REVOKE = "ATTEST_REVOKE";
    private static final String OP_BATCH_QUERY = "BATCH_QUERY";

    private final RecipientRepository recipientRepository;
    private final AttestationRepository attestationRepository;
    private final ConsentRepository consentRepository;
    private final BatchQueryRepository batchQueryRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final BatchBlockAuditService blockAuditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttestService(RecipientRepository recipientRepository,
                         AttestationRepository attestationRepository,
                         ConsentRepository consentRepository,
                         BatchQueryRepository batchQueryRepository,
                         IdempotencyRepository idempotencyRepository,
                         BatchBlockAuditService blockAuditService,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.recipientRepository = recipientRepository;
        this.attestationRepository = attestationRepository;
        this.consentRepository = consentRepository;
        this.batchQueryRepository = batchQueryRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.blockAuditService = blockAuditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 接收方 ----------

    /**
     * 登记接收方：同 requestId 同参数重放返回原结果；重复登记返回 409。
     */
    @Transactional
    public RecipientResponse registerRecipient(RecipientRegisterRequest request) {
        String fingerprint = OP_RECIPIENT_REGISTER + "|" + request.recipientId() + "|" + request.displayName();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), RecipientResponse.class);
        }

        try {
            recipientRepository.insertRecipient(request.recipientId(), request.displayName());
        } catch (DuplicateKeyException concurrent) {
            // 并发同 requestId 同参数登记：首个事务已提交，返回其快照而非报重复
            Optional<IdempotencyRow> committed = idempotencyRepository.find(request.requestId());
            if (committed.isPresent() && committed.get().paramsFingerprint().equals(fingerprint)) {
                return readSnapshot(committed.get().responseBody(), RecipientResponse.class);
            }
            throw ApiException.conflict(CODE_RECIPIENT_ALREADY_EXISTS, "接收方已存在");
        }
        RecipientRow row = recipientRepository.findRecipient(request.recipientId()).orElseThrow();
        RecipientResponse response = toRecipientResponse(row);
        storeSuccess(request.requestId(), OP_RECIPIENT_REGISTER, fingerprint, response);
        return response;
    }

    /**
     * 整体禁用接收方：只允许从 ENABLED 变为 DISABLED；禁用后所有新批次查询 403。
     */
    @Transactional
    public RecipientResponse disableRecipient(RecipientDisableRequest request) {
        String fingerprint = OP_RECIPIENT_DISABLE + "|" + request.recipientId();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), RecipientResponse.class);
        }

        // 锁接收方行后复查幂等：并发同 requestId 禁用时后到者直接返回首个结果
        recipientRepository.findRecipientForUpdate(request.recipientId())
                .orElseThrow(() -> ApiException.notFound(CODE_RECIPIENT_NOT_FOUND, "接收方不存在"));
        replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), RecipientResponse.class);
        }

        Instant now = Instant.now(clock);
        boolean changed = recipientRepository.disableRecipient(request.recipientId(), now);
        if (!changed) {
            throw ApiException.conflict(CODE_RECIPIENT_ALREADY_DISABLED, "接收方已被整体禁用");
        }
        RecipientResponse response = toRecipientResponse(
                recipientRepository.findRecipient(request.recipientId()).orElseThrow());
        storeSuccess(request.requestId(), OP_RECIPIENT_DISABLE, fingerprint, response);
        return response;
    }

    // ---------- 证明提交（版本化续签）与撤销 ----------

    /**
     * 提交证明：作用域精确到“接收方＋用途＋代次”；到期必须晚于提交时刻；
     * 同作用域再次提交为续签，旧版本置 SUPERSEDED，新版本号在锁内分配。
     */
    @Transactional
    public AttestationResponse submitAttestation(AttestSubmitRequest request) {
        Instant now = Instant.now(clock);
        String fingerprint = attestFingerprint(request);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), AttestationResponse.class);
        }

        recipientRepository.findRecipient(request.recipientId())
                .orElseThrow(() -> ApiException.notFound(CODE_RECIPIENT_NOT_FOUND, "接收方不存在"));
        if (!request.expiresAt().isAfter(now)) {
            throw ApiException.badRequest(CODE_ATTESTATION_EXPIRED, "证明到期必须晚于提交时刻");
        }

        String attestationId = attestationId(request.recipientId(), request.purpose(), request.epoch());
        // 串行化同作用域并发首次提交：作用域行存在则加锁，否则插入（唯一键兜底并发插入）
        String lockedId = attestationRepository
                .lockScope(request.recipientId(), request.purpose(), request.epoch())
                .orElseGet(() -> {
                    try {
                        attestationRepository.insertScope(attestationId, request.recipientId(),
                                request.purpose(), request.epoch());
                    } catch (DuplicateKeyException concurrent) {
                        // 并发首次提交：等待对方提交后加同一把作用域行锁
                    }
                    return attestationRepository
                            .lockScope(request.recipientId(), request.purpose(), request.epoch())
                            .orElseThrow(() -> new IllegalStateException("证明作用域锁定失败"));
                });

        // 取得作用域锁后复查幂等：并发同 requestId 续签时后到者不得再分配新版本
        replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), AttestationResponse.class);
        }

        attestationRepository.supersedeActive(request.recipientId(), request.purpose(), request.epoch());
        int version = attestationRepository.currentMaxVersion(lockedId) + 1;
        AttestationRow row = new AttestationRow(lockedId, version, request.recipientId(), request.purpose(),
                request.epoch(), request.expiresAt(), request.claimDigest(), AttestationStatus.ACTIVE, now, null);
        attestationRepository.insertVersion(row, request.requestId());

        AttestationResponse response = toAttestationResponse(row);
        storeSuccess(request.requestId(), OP_ATTEST, fingerprint, response);
        return response;
    }

    /**
     * 撤销证明：只影响后续查询；已生成快照及其证明版本不可改写。
     */
    @Transactional
    public AttestationResponse revokeAttestation(AttestRevokeRequest request) {
        String fingerprint = OP_ATTEST_REVOKE + "|" + request.recipientId() + "|"
                + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), AttestationResponse.class);
        }

        recipientRepository.findRecipient(request.recipientId())
                .orElseThrow(() -> ApiException.notFound(CODE_RECIPIENT_NOT_FOUND, "接收方不存在"));
        attestationRepository.lockScope(request.recipientId(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_ATTESTATION_NOT_FOUND, "证明不存在"));

        // 取得作用域锁后复查幂等：并发同 requestId 撤销时后到者直接返回首个结果
        replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), AttestationResponse.class);
        }

        AttestationRow active = attestationRepository
                .findActiveForUpdate(request.recipientId(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.conflict(
                        CODE_ATTESTATION_ALREADY_REVOKED, "证明无生效版本，无法撤销"));

        Instant now = Instant.now(clock);
        boolean revoked = attestationRepository.revokeActive(
                request.recipientId(), request.purpose(), request.epoch(), now);
        if (!revoked) {
            throw ApiException.conflict(CODE_ATTESTATION_ALREADY_REVOKED, "证明已撤销");
        }
        AttestationResponse response = toAttestationResponse(
                attestationRepository.findVersion(active.attestationId(), active.version()).orElseThrow());
        storeSuccess(request.requestId(), OP_ATTEST_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 查询证明历史：返回某作用域全部版本（含 SUPERSEDED/REVOKED），按版本升序。
     */
    @Transactional(readOnly = true)
    public List<AttestationResponse> attestationHistory(String recipientId, Purpose purpose, int epoch) {
        String attestationId = attestationRepository.findScopeId(recipientId, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_ATTESTATION_NOT_FOUND, "证明不存在"));
        return attestationRepository.findHistory(attestationId).stream()
                .map(this::toAttestationResponse)
                .toList();
    }

    // ---------- 批量查询门禁与快照 ----------

    /**
     * 批量数据查询：接收方未禁用，且每个主体的当前授权代次都存在该接收方的未到期证明时，
     * 整批通过并生成固化授权代次与证明版本的不可改写快照；否则整次 403、无任何部分数据，
     * 阻断明细（主体＋原因，稳定排序）登记为 BLOCKED 批次可供查询。
     */
    @Transactional
    public BatchQueryResponse batchQuery(BatchQueryRequest request) {
        List<String> subjects = distinctOrdered(request.subjectKeys());
        String fingerprint = OP_BATCH_QUERY + "|" + request.recipientId() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + sha256(String.join(",", subjects));
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), BatchQueryResponse.class);
        }

        Instant now = Instant.now(clock);
        // 先锁接收方行：与“接收方禁用”按提交顺序互斥裁决
        RecipientRow recipient = recipientRepository.findRecipientForUpdate(request.recipientId())
                .orElseThrow(() -> ApiException.notFound(CODE_RECIPIENT_NOT_FOUND, "接收方不存在"));

        // 取得接收方锁后复查幂等：并发同 requestId 查询时后到者返回首个快照，不生成第二批次
        replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), BatchQueryResponse.class);
        }

        List<BlockRow> blocks = new ArrayList<>();
        if (recipient.status() == RecipientStatus.DISABLED) {
            for (String subject : subjects) {
                blocks.add(new BlockRow(null, subject, null, REASON_RECIPIENT_DISABLED));
            }
            return rejectBatch(request, now, blocks);
        }

        // 逐主体（请求去重后的稳定顺序）锁当前授权代次与证明版本，收集通过项，发现任一阻断即整批拒绝
        List<GatedSubject> passed = new ArrayList<>();
        for (String subject : subjects) {
            Optional<GrantRow> grant = consentRepository.lockLatestGrant(subject, request.purpose());
            if (grant.isEmpty()) {
                blocks.add(new BlockRow(null, subject, null, REASON_GRANT_NOT_FOUND));
                continue;
            }
            GrantRow grantRow = grant.get();
            if (grantRow.status() == GrantStatus.REVOKED) {
                blocks.add(new BlockRow(null, subject, grantRow.epoch(), REASON_GRANT_REVOKED));
                continue;
            }
            int epoch = grantRow.epoch();
            if (attestationRepository.lockScope(request.recipientId(), request.purpose(), epoch).isEmpty()) {
                blocks.add(new BlockRow(null, subject, epoch, REASON_ATTESTATION_MISSING));
                continue;
            }
            Optional<AttestationRow> attestation = attestationRepository
                    .findActiveForUpdate(request.recipientId(), request.purpose(), epoch);
            if (attestation.isEmpty()) {
                blocks.add(new BlockRow(null, subject, epoch, REASON_ATTESTATION_MISSING));
                continue;
            }
            AttestationRow attestationRow = attestation.get();
            if (!attestationRow.expiresAt().isAfter(now)) {
                blocks.add(new BlockRow(null, subject, epoch, REASON_ATTESTATION_EXPIRED));
                continue;
            }
            Optional<RecordRow> record = consentRepository.findRecord(
                    subject, request.purpose(), epoch, request.recordKey());
            if (record.isEmpty()) {
                blocks.add(new BlockRow(null, subject, epoch, REASON_RECORD_NOT_FOUND));
                continue;
            }
            passed.add(new GatedSubject(subject, grantRow, attestationRow, record.get()));
        }

        if (!blocks.isEmpty()) {
            return rejectBatch(request, now, blocks);
        }

        String batchId = newBatchId();
        batchQueryRepository.insertBatch(new BatchRow(batchId, request.recipientId(), request.purpose(),
                request.recordKey(), "SNAPSHOTTED", now), request.requestId());
        List<SnapshotItem> items = new ArrayList<>();
        for (GatedSubject gated : passed) {
            batchQueryRepository.insertSnapshotItem(new SnapshotRow(batchId, gated.subject(),
                    gated.grant().epoch(), gated.attestation().attestationId(),
                    gated.attestation().version(), request.recordKey(), gated.record().payload()));
            items.add(new SnapshotItem(gated.subject(), gated.grant().epoch(),
                    gated.attestation().attestationId(), gated.attestation().version(),
                    request.recordKey(), gated.record().payload()));
        }
        BatchQueryResponse response = new BatchQueryResponse(batchId, request.recipientId(),
                request.purpose(), request.recordKey(), now, items);
        storeSuccess(request.requestId(), OP_BATCH_QUERY, fingerprint, response);
        return response;
    }

    /**
     * 查询批次详情：快照批次返回每个主体所用授权代次与证明版本；阻断批次返回稳定明细。
     */
    @Transactional(readOnly = true)
    public BatchDetailResponse getBatch(String batchId) {
        BatchRow batch = batchQueryRepository.findBatch(batchId)
                .orElseThrow(() -> ApiException.notFound(CODE_BATCH_NOT_FOUND, "批次不存在"));
        if ("BLOCKED".equals(batch.status())) {
            List<BatchBlockDetail> blocks = batchQueryRepository.findBlocks(batchId).stream()
                    .map(row -> new BatchBlockDetail(row.subjectKey(), row.epoch(), row.reason()))
                    .toList();
            return new BatchDetailResponse(batch.batchId(), batch.recipientId(), batch.purpose(),
                    batch.recordKey(), batch.status(), batch.createdAt(), List.of(), blocks);
        }
        List<SnapshotItem> items = batchQueryRepository.findSnapshotItems(batchId).stream()
                .map(row -> new SnapshotItem(row.subjectKey(), row.epoch(), row.attestationId(),
                        row.attestationVersion(), row.recordKey(), row.payload()))
                .toList();
        return new BatchDetailResponse(batch.batchId(), batch.recipientId(), batch.purpose(),
                batch.recordKey(), batch.status(), batch.createdAt(), items, List.of());
    }

    // ---------- 内部辅助 ----------

    /**
     * 门禁失败：在独立事务登记 BLOCKED 批次与阻断明细（外层随后回滚，不占幂等键），
     * 再抛 403，响应明细稳定列出主体与原因。
     */
    private BatchQueryResponse rejectBatch(BatchQueryRequest request,
                                           Instant now, List<BlockRow> rawBlocks) {
        String batchId = newBatchId();
        List<BlockRow> orderedBlocks = rawBlocks.stream()
                .sorted((a, b) -> a.subjectKey().compareTo(b.subjectKey()))
                .map(block -> new BlockRow(batchId, block.subjectKey(), block.epoch(), block.reason()))
                .toList();
        blockAuditService.recordBlocked(batchId, request.recipientId(), request.purpose(),
                request.recordKey(), now, orderedBlocks);
        List<BatchBlockDetail> details = orderedBlocks.stream()
                .map(block -> new BatchBlockDetail(block.subjectKey(), block.epoch(), block.reason()))
                .toList();
        Map<String, Object> detailPayload = new LinkedHashMap<>();
        detailPayload.put("batchId", batchId);
        detailPayload.put("blocks", details);
        throw ApiException.forbidden(CODE_BATCH_FORBIDDEN, "批次查询门禁未通过，整批拒绝", detailPayload);
    }

    /**
     * 已通过门禁的单主体上下文。
     */
    private record GatedSubject(String subject, GrantRow grant, AttestationRow attestation, RecordRow record) {
    }

    private List<String> distinctOrdered(List<String> input) {
        return new ArrayList<>(new LinkedHashSet<>(input));
    }

    private String attestationId(String recipientId, Purpose purpose, int epoch) {
        return recipientId + "#" + purpose.name() + "#" + epoch;
    }

    private String attestFingerprint(AttestSubmitRequest request) {
        // attestKey 指纹含接收方、用途代次、到期（UTC）与声明摘要
        return OP_ATTEST + "|" + request.recipientId() + "|" + request.purpose() + "|"
                + request.epoch() + "|" + request.expiresAt() + "|" + request.claimDigest();
    }

    private String newBatchId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private RecipientResponse toRecipientResponse(RecipientRow row) {
        return new RecipientResponse(row.recipientId(), row.status(), row.displayName(),
                row.createdAt());
    }

    private AttestationResponse toAttestationResponse(AttestationRow row) {
        return new AttestationResponse(row.attestationId(), row.version(), row.recipientId(),
                row.purpose(), row.epoch(), row.expiresAt(), row.claimDigest(), row.status(),
                row.submittedAt(), row.revokedAt());
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
            IdempotencyRow committed = idempotencyRepository.find(requestId).orElseThrow(() -> concurrent);
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
