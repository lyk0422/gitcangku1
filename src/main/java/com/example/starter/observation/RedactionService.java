package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 观测附件脱敏业务服务：附件登记、脱敏批次、撤销、物理清除、公开/证据/诊断查询。
 *
 * <p>写操作沿用 request_log 占位 + 行锁的单事务模型：脱敏批次先锁观测行，再校验整批后态
 * （观测非墓碑、附件全部归属、规则版本全部已登记且未撤销、同附件未重复套用同一规则版本），
 * 任一失败直接抛出、整批回滚，不产生任何脱敏版本，失败不占 requestId 键；全部通过后在同一
 * 事务内追加版本并回填响应。同一观测行的行锁串行化同附件并发，配合数据库唯一约束，保证
 * “同一附件同一规则版本并发只生成一个版本”。
 */
@Service
public class RedactionService {

    private static final String SEPARATOR = "\u0001";

    private final ObservationRepository observationRepository;
    private final AttachmentRepository attachmentRepository;
    private final RedactionRuleRepository ruleRepository;
    private final RedactionRepository redactionRepository;
    private final RequestLogRepository requestLogRepository;
    private final SnapshotRefRepository snapshotRefRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RedactionService(ObservationRepository observationRepository,
                            AttachmentRepository attachmentRepository,
                            RedactionRuleRepository ruleRepository,
                            RedactionRepository redactionRepository,
                            RequestLogRepository requestLogRepository,
                            SnapshotRefRepository snapshotRefRepository,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.observationRepository = observationRepository;
        this.attachmentRepository = attachmentRepository;
        this.ruleRepository = ruleRepository;
        this.redactionRepository = redactionRepository;
        this.requestLogRepository = requestLogRepository;
        this.snapshotRefRepository = snapshotRefRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 登记附件元数据到指定观测记录；观测不存在返回 404，墓碑观测返回 422，附件标识重复返回 409。
     */
    @Transactional
    public AttachmentMeta registerAttachment(String observationId, RegisterAttachmentRequest request) {        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.unprocessable("observation already deleted: " + observationId);
        }
        AttachmentMeta attachment = new AttachmentMeta(
                request.attachmentId(), observationId, request.fileName(), request.contentType(),
                request.sizeBytes(), request.originalSummary(), false, null, null);
        try {
            attachmentRepository.insert(attachment);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("attachment already exists: " + request.attachmentId(), null);
        }
        return attachmentRepository.find(request.attachmentId())
                .orElseThrow(() -> new IllegalStateException("attachment missing after insert"));
    }

    /**
     * 登记新的脱敏规则版本；同标识同版本重复登记返回 409。
     */
    @Transactional
    public RedactionRule registerRule(String ruleCode, int ruleVersion, String ruleName) {
        RedactionRule rule = new RedactionRule(ruleCode, ruleVersion, ruleName, false, null, Instant.now(clock));
        try {
            ruleRepository.insert(rule);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(
                    "rule version already registered: " + ruleCode + "@" + ruleVersion, null);
        }
        return rule;
    }

    /**
     * 撤销规则版本登记：未登记 404，已撤销重复操作 422；撤销后仅不可再用于新脱敏，既有脱敏版本保留。
     */
    @Transactional
    public RedactionRule revokeRule(String ruleCode, int ruleVersion) {
        RedactionRule rule = ruleRepository.find(ruleCode, ruleVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "rule version not found: " + ruleCode + "@" + ruleVersion));
        if (rule.revoked()) {
            throw ApiException.unprocessable(
                    "rule version already revoked: " + ruleCode + "@" + ruleVersion);
        }
        ruleRepository.markRevoked(ruleCode, ruleVersion, Instant.now(clock));
        return ruleRepository.find(ruleCode, ruleVersion)
                .orElseThrow(() -> new IllegalStateException("rule missing after revoke"));
    }

    /**
     * 提交附件脱敏批次：先校验全部条目，全部通过后在单事务内为每个附件生成递增脱敏版本。
     */
    @Transactional
    public RedactionBatchOutcome createRedactions(String observationId, CreateRedactionRequest request) {
        List<String> itemFingerprints = new ArrayList<>();
        for (CreateRedactionRequest.RedactionItem item : request.items()) {
            itemFingerprints.add(item.attachmentId() + ":" + item.ruleCode() + ":" + item.ruleVersion()
                    + ":" + item.redactedSummary() + ":" + item.replacementNote());
        }
        String fingerprint = fingerprint("REDACT", observationId, request.batchId(), request.operator(),
                request.reason(), String.join(";", itemFingerprints));
        RedactionBatchOutcome replayed = checkBatchReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        RedactionBatchOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "REDACT");
        if (concurrent != null) {
            return concurrent;
        }

        // 锁观测行：串行化同一观测下附件的并发脱敏，版本号分配在锁内完成。
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.unprocessable("observation already deleted: " + observationId);
        }

        // 批次父行先占位：batch_id 被其他请求复用时直接 409；批次随本事务提交或随失败整体回滚。
        Instant now = Instant.now(clock);
        try {
            redactionRepository.insertBatch(request.batchId(), request.requestId(), observationId,
                    request.operator(), request.reason(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("batchId already used by another request: " + request.batchId(), null);
        }

        // 第一阶段：校验整批后态，任何一条失败都在写入版本前抛出，随事务回滚不留部分结果。
        Set<String> distinctAttachments = new LinkedHashSet<>();
        for (CreateRedactionRequest.RedactionItem item : request.items()) {
            if (!distinctAttachments.add(item.attachmentId())) {
                throw ApiException.unprocessable(
                        "same attachment appears more than once in one redaction batch: " + item.attachmentId());
            }
        }
        List<ValidatedItem> validated = new ArrayList<>();
        for (CreateRedactionRequest.RedactionItem item : request.items()) {
            AttachmentMeta attachment = attachmentRepository.find(item.attachmentId())
                    .orElseThrow(() -> ApiException.notFound(
                            "attachment not found: " + item.attachmentId()));
            if (!attachment.observationId().equals(observationId)) {
                throw ApiException.forbidden(
                        "attachment does not belong to observation " + observationId + ": " + item.attachmentId());
            }
            RedactionRule rule = ruleRepository.find(item.ruleCode(), item.ruleVersion())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "rule version not registered: " + item.ruleCode() + "@" + item.ruleVersion()));
            if (rule.revoked()) {
                throw ApiException.unprocessable(
                        "rule version revoked: " + item.ruleCode() + "@" + item.ruleVersion());
            }
            List<RedactionVersion> existing = redactionRepository.findByAttachment(item.attachmentId());
            boolean ruleAlreadyApplied = existing.stream().anyMatch(v ->
                    v.ruleCode().equals(item.ruleCode()) && v.ruleVersion() == item.ruleVersion());
            if (ruleAlreadyApplied) {
                throw ApiException.unprocessable(
                        "rule version already applied to attachment " + item.attachmentId() + ": "
                                + item.ruleCode() + "@" + item.ruleVersion());
            }
            int nextVersion = existing.stream().mapToInt(RedactionVersion::attachmentVersion).max().orElse(0) + 1;
            validated.add(new ValidatedItem(item, attachment, nextVersion));
        }

        // 第二阶段：全部校验通过后一次性提交整批版本。
        List<RedactionVersionResponse> responses = new ArrayList<>();
        for (ValidatedItem validatedItem : validated) {
            CreateRedactionRequest.RedactionItem item = validatedItem.item();
            RedactionVersion version = new RedactionVersion(
                    request.batchId() + ":" + item.attachmentId(),
                    request.batchId(),
                    request.requestId(),
                    item.attachmentId(),
                    observationId,
                    validatedItem.nextVersion(),
                    item.ruleCode(),
                    item.ruleVersion(),
                    item.redactedSummary(),
                    item.replacementNote(),
                    request.reason(),
                    request.operator(),
                    true,
                    now,
                    null, null, null);
            try {
                redactionRepository.insert(version);
            } catch (DuplicateKeyException e) {
                // 唯一约束兜底：并发下同附件同规则版本只允许一个版本；批次标识碰撞也在此拒绝。
                throw ApiException.conflict(
                        "concurrent redaction conflict for attachment " + item.attachmentId()
                                + " with rule " + item.ruleCode() + "@" + item.ruleVersion(), null);
            }
            responses.add(RedactionVersionResponse.evidenceView(version));
        }

        RedactionBatchResponse body =
                new RedactionBatchResponse(request.requestId(), request.batchId(), now, List.copyOf(responses));
        return completeBatch(request.requestId(), body);
    }

    /**
     * 撤销脱敏版本的公开展示资格；版本不存在返回 404，新版本重复撤销返回 422。版本与理由全部保留。
     */
    @Transactional
    public RedactionVersionOutcome revokeRedaction(String redactionId, RevokeRedactionRequest request) {
        String fingerprint = fingerprint("REVOKE", redactionId, request.reason(), request.operator());
        RedactionVersionOutcome replayed = checkVersionReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        RedactionVersionOutcome concurrent = insertVersionPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        RedactionVersion version = redactionRepository.findByIdForUpdate(redactionId)
                .orElseThrow(() -> ApiException.notFound("redaction version not found: " + redactionId));
        if (!version.publicActive()) {
            throw ApiException.unprocessable("redaction version already revoked: " + redactionId);
        }
        Instant now = Instant.now(clock);
        boolean updated = redactionRepository.markRevoked(redactionId, now, request.reason(), request.operator());
        if (!updated) {
            // 并发撤销：行锁内重读时仍有效才会走到更新，此处仅为防御性分支。
            throw ApiException.unprocessable("redaction version already revoked: " + redactionId);
        }
        RedactionVersion revoked = version.revoked(now, request.reason(), request.operator());
        return completeVersion(request.requestId(), revoked);
    }

    /**
     * 物理清除附件原始内容；附件不存在返回 404，已清除返回 422。已固化证据与脱敏版本均不受影响。
     */
    @Transactional
    public AttachmentClearOutcome clearAttachmentContent(String attachmentId, ClearAttachmentContentRequest request) {
        String fingerprint = fingerprint("CLEAR", attachmentId, request.reason(), request.operator());
        AttachmentClearOutcome replayed = checkClearReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        AttachmentClearOutcome concurrent = insertClearPlaceholder(request.requestId(), fingerprint);
        if (concurrent != null) {
            return concurrent;
        }

        AttachmentMeta attachment = attachmentRepository.find(attachmentId)
                .orElseThrow(() -> ApiException.notFound("attachment not found: " + attachmentId));
        if (attachment.contentCleared()) {
            throw ApiException.unprocessable("attachment content already cleared: " + attachmentId);
        }
        Instant now = Instant.now(clock);
        boolean updated = attachmentRepository.clearContent(attachmentId, now);
        if (!updated) {
            throw ApiException.unprocessable("attachment content already cleared: " + attachmentId);
        }
        return completeClear(request.requestId(), attachmentId, now);
    }

    /**
     * 公开查询附件的最新有效脱敏版本；附件不存在或无有效版本均返回 404，不返回原始摘要。
     */
    @Transactional(readOnly = true)
    public RedactionVersionResponse getPublicLatest(String attachmentId) {
        ensureAttachmentExists(attachmentId);
        RedactionVersion version = redactionRepository.findPublicLatest(attachmentId)
                .orElseThrow(() -> ApiException.notFound(
                        "no active redaction version for attachment: " + attachmentId));
        return RedactionVersionResponse.publicView(version);
    }

    /**
     * 公开查询观测下全部附件的最新有效脱敏版本；观测不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<RedactionVersionResponse> listPublicLatest(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return redactionRepository.findPublicLatestByObservation(observationId).stream()
                .map(RedactionVersionResponse::publicView)
                .toList();
    }

    /**
     * 证据查询：返回附件原始摘要（清除后为 null）与全部脱敏版本（含已撤销）。
     */
    @Transactional(readOnly = true)
    public AttachmentEvidenceResponse getEvidence(String attachmentId) {
        AttachmentMeta attachment = attachmentRepository.find(attachmentId)
                .orElseThrow(() -> ApiException.notFound("attachment not found: " + attachmentId));
        return AttachmentEvidenceResponse.of(attachment, redactionRepository.findByAttachment(attachmentId));
    }

    /**
     * 证据查询：按版本标识读取指定脱敏版本（含已撤销）。
     */
    @Transactional(readOnly = true)
    public RedactionVersionResponse getRedactionEvidence(String redactionId) {
        RedactionVersion version = redactionRepository.findById(redactionId)
                .orElseThrow(() -> ApiException.notFound("redaction version not found: " + redactionId));
        return RedactionVersionResponse.evidenceView(version);
    }

    /**
     * 证据查询：观测下固化的全部合并/解决附件证据引用，按固化时刻升序。
     */
    @Transactional(readOnly = true)
    public List<SnapshotRefResponse> listSnapshotRefs(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        return snapshotRefRepository.findByObservation(observationId).stream()
                .map(SnapshotRefResponse::of)
                .toList();
    }

    /**
     * 诊断查询：UTC 左闭右开区间 [from, to) 内脱敏/撤销实际数量、与要求数量的差额、
     * 实际时长（首末版本生成时刻之差）与要求时长（区间长度）及差额。
     */
    @Transactional(readOnly = true)
    public RedactionDiagnosticsResponse diagnostics(Instant from, Instant to, Integer requiredCount) {
        if (from == null || to == null) {
            throw ApiException.badRequest("from and to are required");
        }
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("expected half-open interval [from, to) but from >= to");
        }
        int createdCount = redactionRepository.countCreatedBetween(from, to);
        int revokedCount = redactionRepository.countRevokedBetween(from, to);
        Instant first = redactionRepository.findFirstCreatedAtBetween(from, to).orElse(null);
        Instant last = redactionRepository.findLastCreatedAtBetween(from, to).orElse(null);
        long requiredDuration = Duration.between(from, to).toSeconds();
        long actualDuration = first == null ? 0L : Duration.between(first, last).toSeconds();
        Integer countGap = requiredCount == null ? null : createdCount - requiredCount;
        return new RedactionDiagnosticsResponse(
                from, to, requiredDuration, createdCount, revokedCount, requiredCount, countGap,
                first, last, actualDuration, actualDuration - requiredDuration);
    }

    private void ensureAttachmentExists(String attachmentId) {
        attachmentRepository.find(attachmentId)
                .orElseThrow(() -> ApiException.notFound("attachment not found: " + attachmentId));
    }

    // ---------- 幂等辅助 ----------

    private RedactionBatchOutcome checkBatchReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new RedactionBatchOutcome(entry.responseStatus(),
                readBody(entry.responseBody(), RedactionBatchResponse.class));
    }

    private RedactionVersionOutcome checkVersionReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new RedactionVersionOutcome(entry.responseStatus(),
                readBody(entry.responseBody(), RedactionVersionResponse.class));
    }

    private AttachmentClearOutcome checkClearReplay(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return readClearBody(entry.responseBody());
    }

    private RedactionBatchOutcome insertPlaceholder(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new RedactionBatchOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), RedactionBatchResponse.class));
        }
    }

    private RedactionVersionOutcome insertVersionPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "REVOKE");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new RedactionVersionOutcome(entry.responseStatus(),
                    readBody(entry.responseBody(), RedactionVersionResponse.class));
        }
    }

    private AttachmentClearOutcome insertClearPlaceholder(String requestId, String fingerprint) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, "CLEAR");
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return readClearBody(entry.responseBody());
        }
    }

    private RedactionBatchOutcome completeBatch(String requestId, RedactionBatchResponse body) {
        complete(requestId, HttpStatus.OK.value(), body);
        return new RedactionBatchOutcome(HttpStatus.OK.value(), body);
    }

    private RedactionVersionOutcome completeVersion(String requestId, RedactionVersion version) {
        RedactionVersionResponse body = RedactionVersionResponse.evidenceView(version);
        complete(requestId, HttpStatus.OK.value(), body);
        return new RedactionVersionOutcome(HttpStatus.OK.value(), body);
    }

    private AttachmentClearOutcome completeClear(String requestId, String attachmentId, Instant clearedAtUtc) {
        AttachmentClearOutcome body = new AttachmentClearOutcome(
                HttpStatus.OK.value(), attachmentId, clearedAtUtc);
        complete(requestId, HttpStatus.OK.value(), body);
        return body;
    }

    private void complete(String requestId, int status, Object body) {
        try {
            requestLogRepository.complete(requestId, status, objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private <T> T readBody(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    private AttachmentClearOutcome readClearBody(String json) {
        return readBody(json, AttachmentClearOutcome.class);
    }

    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 脱敏批次结果：HTTP 状态码与响应体。
     */
    public record RedactionBatchOutcome(int status, RedactionBatchResponse body) {
    }

    /**
     * 撤销/单个脱敏版本操作结果：HTTP 状态码与响应体。
     */
    public record RedactionVersionOutcome(int status, RedactionVersionResponse body) {
    }

    /**
     * 物理清除结果：HTTP 状态码、附件标识与清除时刻（UTC）。
     */
    public record AttachmentClearOutcome(int status, String attachmentId, Instant clearedAtUtc) {
    }

    /**
     * 批次内单条已校验条目及其分配到的附件脱敏版本号。
     */
    private record ValidatedItem(CreateRedactionRequest.RedactionItem item,
                                 AttachmentMeta attachment,
                                 int nextVersion) {
    }
}
