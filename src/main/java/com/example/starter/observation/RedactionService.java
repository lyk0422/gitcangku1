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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 观测附件脱敏业务服务。
 *
 * <p>写操作（登记附件/登记规则/撤销规则/脱敏/撤销脱敏/物理清除）均沿用 request_log 占位 +
 * 行锁 + 同事务提交机制：失败回滚不占键，同 requestId 同参重放首次完整响应、异参 409。
 *
 * <p>脱敏请求先校验全部附件项的归属、规则版本与状态，任一失败整次不产生脱敏版本；
 * 校验通过后对观测当前行加锁（与合并/解决/删除按提交顺序串行），逐附件分配递增脱敏版本号，
 * 在单事务内插入全部脱敏版本。同一附件同一规则版本由唯一约束保证并发只生成一个版本。
 *
 * <p>脱敏只创建新的受限展示版本：原始附件摘要、原始观测与既有合并证据均不可修改；
 * 撤销仅取消公开展示资格，保留脱敏版本、理由与撤销时刻。
 */
@Service
public class RedactionService {

    private static final String SEPARATOR = "\u0001";

    private final ObservationRepository observationRepository;
    private final AttachmentRepository attachmentRepository;
    private final RedactionRuleRepository ruleRepository;
    private final AttachmentRedactionRepository redactionRepository;
    private final EvidenceRefRepository evidenceRefRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RedactionService(ObservationRepository observationRepository,
                            AttachmentRepository attachmentRepository,
                            RedactionRuleRepository ruleRepository,
                            AttachmentRedactionRepository redactionRepository,
                            EvidenceRefRepository evidenceRefRepository,
                            RequestLogRepository requestLogRepository,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.observationRepository = observationRepository;
        this.attachmentRepository = attachmentRepository;
        this.ruleRepository = ruleRepository;
        this.redactionRepository = redactionRepository;
        this.evidenceRefRepository = evidenceRefRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 规则版本管理 ----------

    /**
     * 登记脱敏规则版本；仅脱敏官角色可执行。规则版本已存在返回 409。
     */
    @Transactional
    public WriteOutcome<RedactionRuleResponse> registerRule(RegisterRuleRequest request, RequesterRole role) {
        requireOfficer(role);
        String fingerprint = fingerprint("REGISTER_RULE", request.ruleVersion(), request.description());
        WriteOutcome<RedactionRuleResponse> replayed = checkReplay(request.requestId(), fingerprint,
                RedactionRuleResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<RedactionRuleResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "REGISTER_RULE", RedactionRuleResponse.class);
        if (concurrent != null) {
            return concurrent;
        }
        if (ruleRepository.findByRuleVersion(request.ruleVersion()).isPresent()) {
            throw ApiException.conflict("rule version already registered: " + request.ruleVersion(), null);
        }
        RedactionRuleRecord record = new RedactionRuleRecord(request.ruleVersion(), request.description(),
                true, null, Instant.now(clock), null);
        try {
            ruleRepository.insert(record);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("rule version already registered: " + request.ruleVersion(), null);
        }
        return complete(request.requestId(), HttpStatus.CREATED, RedactionRuleResponse.of(record));
    }

    /**
     * 撤销脱敏规则版本；仅脱敏官角色可执行。撤销后该规则不可用于新脱敏请求，既有脱敏版本不受影响。
     * 规则不存在返回 404；已撤销返回 422。
     */
    @Transactional
    public WriteOutcome<RedactionRuleResponse> revokeRule(String ruleVersion, RevokeRuleRequest request,
                                                          RequesterRole role) {
        requireOfficer(role);
        String fingerprint = fingerprint("REVOKE_RULE", ruleVersion, request.reason());
        WriteOutcome<RedactionRuleResponse> replayed = checkReplay(request.requestId(), fingerprint,
                RedactionRuleResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<RedactionRuleResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "REVOKE_RULE", RedactionRuleResponse.class);
        if (concurrent != null) {
            return concurrent;
        }
        RedactionRuleRecord record = ruleRepository.findByRuleVersion(ruleVersion)
                .orElseThrow(() -> ApiException.notFound("redaction rule not found: " + ruleVersion));
        if (!record.active()) {
            throw ApiException.unprocessable("rule version already revoked: " + ruleVersion);
        }
        Instant revokedAtUtc = Instant.now(clock);
        ruleRepository.revoke(ruleVersion, request.reason(), revokedAtUtc);
        RedactionRuleRecord revoked = new RedactionRuleRecord(record.ruleVersion(), record.description(),
                false, request.reason(), record.registeredAtUtc(), revokedAtUtc);
        return complete(request.requestId(), HttpStatus.OK, RedactionRuleResponse.of(revoked));
    }

    // ---------- 附件登记与物理清除 ----------

    /**
     * 登记观测附件；仅脱敏官角色可执行。观测不存在或为已删除墓碑返回 404；附件标识已存在返回 409。
     */
    @Transactional
    public WriteOutcome<AttachmentResponse> registerAttachment(String observationId,
                                                               RegisterAttachmentRequest request,
                                                               RequesterRole role) {
        requireOfficer(role);
        if (!observationId.equals(request.observationId())) {
            throw ApiException.unprocessable(
                    "observationId in path and body must match: " + observationId + " != " + request.observationId());
        }
        String fingerprint = fingerprint("REGISTER_ATTACHMENT", observationId, request.attachmentId(),
                request.contentSummary());
        WriteOutcome<AttachmentResponse> replayed = checkReplay(request.requestId(), fingerprint,
                AttachmentResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<AttachmentResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "REGISTER_ATTACHMENT", AttachmentResponse.class);
        if (concurrent != null) {
            return concurrent;
        }
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(request.observationId())
                .orElseThrow(() -> ApiException.notFound("observation not found: " + request.observationId()));
        if (current.deleted()) {
            throw ApiException.notFound("observation already deleted: " + request.observationId());
        }
        if (attachmentRepository.findById(request.attachmentId()).isPresent()) {
            throw ApiException.conflict("attachment already exists: " + request.attachmentId(), null);
        }
        AttachmentRecord record = new AttachmentRecord(request.attachmentId(), request.observationId(),
                request.contentSummary(), false, Instant.now(clock), null);
        try {
            attachmentRepository.insert(record);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("attachment already exists: " + request.attachmentId(), null);
        }
        return complete(request.requestId(), HttpStatus.CREATED, AttachmentResponse.of(record));
    }

    /**
     * 物理清除附件原始内容；仅脱敏官角色可执行。清除后原始摘要不可恢复，
     * 既有脱敏版本、历史与证据引用不受影响。附件不存在返回 404；已清除返回 422。
     */
    @Transactional
    public WriteOutcome<PurgeAttachmentResponse> purgeAttachment(String observationId, String attachmentId,
                                                                 PurgeAttachmentRequest request,
                                                                 RequesterRole role) {
        requireOfficer(role);
        String fingerprint = fingerprint("PURGE_ATTACHMENT", observationId, attachmentId, request.reason());
        WriteOutcome<PurgeAttachmentResponse> replayed = checkReplay(request.requestId(), fingerprint,
                PurgeAttachmentResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<PurgeAttachmentResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "PURGE_ATTACHMENT", PurgeAttachmentResponse.class);
        if (concurrent != null) {
            return concurrent;
        }
        AttachmentRecord attachment = attachmentRepository.findByIdForUpdate(attachmentId)
                .orElseThrow(() -> ApiException.notFound("attachment not found: " + attachmentId));
        if (!attachment.observationId().equals(observationId)) {
            throw ApiException.unprocessable(
                    "attachment " + attachmentId + " does not belong to observation " + observationId);
        }
        if (attachment.contentPurged()) {
            throw ApiException.unprocessable("attachment content already purged: " + attachmentId);
        }
        Instant purgedAtUtc = Instant.now(clock);
        attachmentRepository.purgeContent(attachmentId, purgedAtUtc);
        return complete(request.requestId(), HttpStatus.OK,
                new PurgeAttachmentResponse(attachmentId, true, purgedAtUtc));
    }

    // ---------- 脱敏与撤销 ----------

    /**
     * 提交附件脱敏请求；仅脱敏官角色可执行。
     *
     * <p>先校验全部附件项：附件存在（404）且属于该观测（422）、规则已登记（404）且未撤销（422）、
     * 同一附件未重复使用同一规则版本（422）、观测存在且非墓碑（404）；任一失败整次不产生脱敏版本。
     * 校验通过后对观测当前行加锁，在单事务内为每个附件分配递增脱敏版本号并插入全部版本。
     */
    @Transactional
    public WriteOutcome<RedactionResponse> redact(String observationId, RedactAttachmentsRequest request,
                                                  RequesterRole role) {
        requireOfficer(role);
        String normalizedItems = normalizeItems(request.items());
        String fingerprint = fingerprint("REDACT", observationId, request.redactionId(), normalizedItems);
        WriteOutcome<RedactionResponse> replayed = checkReplay(request.requestId(), fingerprint,
                RedactionResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<RedactionResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "REDACT", RedactionResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        // redactionId 重放判定先于业务校验：同参重放始终返回原结果，异参或跨观测复用一律 409。
        List<AttachmentRedactionRecord> existing = redactionRepository.findByRedactionId(request.redactionId());
        if (!existing.isEmpty()) {
            if (!sameRedactionParams(existing, observationId, request)) {
                throw ApiException.conflict(
                        "redactionId reused with different parameters: " + request.redactionId(), null);
            }
            return complete(request.requestId(), HttpStatus.OK,
                    redactionResponseOf(existing, request.items()));
        }

        // 先完整校验全部附件项，任一失败整次不产生脱敏版本（此时尚未写入任何脱敏行）。
        validateRedactItems(observationId, request.items());

        // 校验通过后对观测当前行加锁：与合并/解决/删除/其他脱敏按提交顺序串行裁决。
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.notFound("observation already deleted: " + observationId);
        }

        Instant createdAtUtc = Instant.now(clock);
        // 加锁阶段：先按附件标识固定顺序锁全部附件行，再按规则版本固定顺序锁全部规则行，
        // 避免并发多附件请求以不同顺序申请行锁造成死锁；锁内完成归属与规则状态终判。
        Map<String, AttachmentRecord> lockedAttachments = new java.util.HashMap<>();
        Map<String, RedactionRuleRecord> lockedRules = new java.util.HashMap<>();
        request.items().stream()
                .map(RedactAttachmentsRequest.RedactItem::attachmentId)
                .distinct()
                .sorted()
                .forEach(attachmentId -> {
                    AttachmentRecord attachment = attachmentRepository.findByIdForUpdate(attachmentId)
                            .orElseThrow(() -> ApiException.notFound("attachment not found: " + attachmentId));
                    if (!attachment.observationId().equals(observationId)) {
                        throw ApiException.unprocessable("attachment " + attachmentId
                                + " does not belong to observation " + observationId);
                    }
                    lockedAttachments.put(attachmentId, attachment);
                });
        request.items().stream()
                .map(RedactAttachmentsRequest.RedactItem::ruleVersion)
                .distinct()
                .sorted()
                .forEach(ruleVersion -> {
                    RedactionRuleRecord rule = ruleRepository.findByRuleVersionForUpdate(ruleVersion)
                            .orElseThrow(() -> ApiException.notFound("redaction rule not found: " + ruleVersion));
                    if (!rule.active()) {
                        throw ApiException.unprocessable("redaction rule version revoked: " + ruleVersion);
                    }
                    lockedRules.put(ruleVersion, rule);
                });

        // 插入阶段：仍按请求顺序分配逐附件递增版本号并插入全部版本（附件行锁已持有，版本分配串行）。
        List<AttachmentRedactionRecord> records = new ArrayList<>();
        for (RedactAttachmentsRequest.RedactItem item : request.items()) {
            AttachmentRecord attachment = lockedAttachments.get(item.attachmentId());
            int nextVersion = redactionRepository.findMaxVersion(attachment.attachmentId()).orElse(0) + 1;
            AttachmentRedactionRecord record = new AttachmentRedactionRecord(
                    request.redactionId(), observationId, attachment.attachmentId(), nextVersion,
                    request.requestId(), item.ruleVersion(),
                    buildDisplaySummary(item.ruleVersion(), item.replacementNote()),
                    item.replacementNote(), true, null, createdAtUtc, null);
            try {
                redactionRepository.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发下同一附件同一规则版本已被其他事务脱敏：由唯一约束收敛为一个版本。
                throw ApiException.unprocessable("rule version " + item.ruleVersion()
                        + " already applied to attachment: " + item.attachmentId());
            }
            records.add(record);
        }
        return complete(request.requestId(), HttpStatus.CREATED, redactionResponseOf(records, request.items()));
    }

    /**
     * 撤销附件脱敏版本的公开展示资格；仅脱敏官角色可执行。
     * 脱敏版本、理由与撤销时刻保留；不能恢复已被物理清除的附件内容。
     * 版本不存在返回 404；已撤销返回 422。
     */
    @Transactional
    public WriteOutcome<RedactionRevokeResponse> revokeRedaction(String observationId, String attachmentId,
                                                                 int redactionVersion,
                                                                 RevokeRedactionRequest request,
                                                                 RequesterRole role) {
        requireOfficer(role);
        String fingerprint = fingerprint("REVOKE_REDACTION", observationId, attachmentId,
                String.valueOf(redactionVersion), request.reason());
        WriteOutcome<RedactionRevokeResponse> replayed = checkReplay(request.requestId(), fingerprint,
                RedactionRevokeResponse.class);
        if (replayed != null) {
            return replayed;
        }
        WriteOutcome<RedactionRevokeResponse> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "REVOKE_REDACTION", RedactionRevokeResponse.class);
        if (concurrent != null) {
            return concurrent;
        }
        AttachmentRedactionRecord record = redactionRepository.findByVersion(attachmentId, redactionVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "redaction version not found: " + attachmentId + "@" + redactionVersion));
        if (!record.observationId().equals(observationId)) {
            throw ApiException.unprocessable(
                    "attachment " + attachmentId + " does not belong to observation " + observationId);
        }
        if (!record.active()) {
            throw ApiException.unprocessable(
                    "redaction version already revoked: " + attachmentId + "@" + redactionVersion);
        }
        Instant revokedAtUtc = Instant.now(clock);
        redactionRepository.revoke(attachmentId, redactionVersion, request.reason(), revokedAtUtc);
        return complete(request.requestId(), HttpStatus.OK,
                new RedactionRevokeResponse(attachmentId, redactionVersion, false, request.reason(), revokedAtUtc));
    }

    // ---------- 查询 ----------

    /**
     * 公开附件视图：只返回最新有效脱敏版本的受限展示摘要；无有效脱敏时返回原始摘要。
     * 观测不存在返回 404；查询不修改任何状态。
     */
    @Transactional(readOnly = true)
    public List<PublicAttachmentView> listPublicAttachments(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        List<PublicAttachmentView> views = new ArrayList<>();
        for (AttachmentRecord attachment : attachmentRepository.findByObservationId(observationId)) {
            Optional<AttachmentRedactionRecord> latestActive =
                    redactionRepository.findLatestActive(attachment.attachmentId());
            if (latestActive.isPresent()) {
                AttachmentRedactionRecord redaction = latestActive.get();
                views.add(new PublicAttachmentView(attachment.attachmentId(), observationId,
                        redaction.displaySummary(), true, redaction.redactionVersion(),
                        redaction.ruleVersion(), redaction.createdAtUtc()));
            } else {
                views.add(new PublicAttachmentView(attachment.attachmentId(), observationId,
                        attachment.contentSummary(), false, null, null, null));
            }
        }
        return views;
    }

    /**
     * 附件证据视图：返回原始摘要与全部脱敏版本历史（含已撤销）；仅证据读取角色可访问。
     */
    @Transactional(readOnly = true)
    public AttachmentEvidenceView getAttachmentEvidence(String observationId, String attachmentId,
                                                        RequesterRole role) {
        requireEvidenceReader(role);
        AttachmentRecord attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound("attachment not found: " + attachmentId));
        if (!attachment.observationId().equals(observationId)) {
            throw ApiException.unprocessable(
                    "attachment " + attachmentId + " does not belong to observation " + observationId);
        }
        List<AttachmentEvidenceView.RedactionVersionView> redactions =
                redactionRepository.findByAttachmentId(attachmentId).stream()
                        .map(r -> new AttachmentEvidenceView.RedactionVersionView(r.redactionVersion(),
                                r.ruleVersion(), r.displaySummary(), r.replacementNote(), r.active(),
                                r.revokeReason(), r.createdAtUtc(), r.revokedAtUtc()))
                        .toList();
        return new AttachmentEvidenceView(attachment.attachmentId(), observationId, attachment.contentSummary(),
                attachment.contentPurged(), attachment.purgedAtUtc(), redactions);
    }

    /**
     * 观测版本证据视图：返回该版本生成时固化的附件证据引用；后续脱敏与撤销不追溯替换。
     * 仅证据读取角色可访问；观测版本不存在返回 404。
     */
    @Transactional(readOnly = true)
    public VersionEvidenceView getVersionEvidence(String observationId, int version, RequesterRole role) {
        requireEvidenceReader(role);
        observationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        List<VersionEvidenceView.Item> items = evidenceRefRepository
                .findByObservationVersion(observationId, version).stream()
                .map(ref -> {
                    AttachmentRecord attachment = attachmentRepository.findById(ref.attachmentId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "attachment missing for evidence ref: " + ref.attachmentId()));
                    String displaySummary = null;
                    String ruleVersion = null;
                    if (ref.redactionVersion() != null) {
                        AttachmentRedactionRecord redaction = redactionRepository
                                .findByVersion(ref.attachmentId(), ref.redactionVersion())
                                .orElseThrow(() -> new IllegalStateException(
                                        "redaction version missing for evidence ref: " + ref.attachmentId()
                                                + "@" + ref.redactionVersion()));
                        displaySummary = redaction.displaySummary();
                        ruleVersion = redaction.ruleVersion();
                    }
                    return new VersionEvidenceView.Item(ref.attachmentId(), ref.redactionVersion(),
                            attachment.contentSummary(), displaySummary, ruleVersion, ref.frozenAtUtc());
                })
                .toList();
        return new VersionEvidenceView(observationId, version, items);
    }

    /**
     * 脱敏诊断：统计 UTC 区间 [fromUtc, toUtc)（左闭右开）内生成的脱敏版本数量与区间时长，
     * 返回实际值、要求值与差额。仅证据读取角色可访问；区间非法返回 400。
     */
    @Transactional(readOnly = true)
    public RedactionDiagnosticsResponse diagnostics(Instant fromUtc, Instant toUtc, long expectedCount,
                                                    Long expectedDurationSeconds, RequesterRole role) {
        requireEvidenceReader(role);
        if (fromUtc == null || toUtc == null || !fromUtc.isBefore(toUtc)) {
            throw ApiException.badRequest("diagnostics window must satisfy fromUtc < toUtc (UTC, half-open)");
        }
        long actualCount = redactionRepository.countCreatedBetween(fromUtc, toUtc);
        long actualDurationSeconds = java.time.Duration.between(fromUtc, toUtc).getSeconds();
        Long durationDifference = expectedDurationSeconds == null
                ? null
                : actualDurationSeconds - expectedDurationSeconds;
        return new RedactionDiagnosticsResponse(actualCount, expectedCount, actualCount - expectedCount,
                actualDurationSeconds, expectedDurationSeconds, durationDifference, fromUtc, toUtc);
    }

    // ---------- 内部辅助 ----------

    /**
     * 生成受限展示摘要：由规则版本与替换说明确定，公开查询返回此文本代替原始摘要。
     */
    static String buildDisplaySummary(String ruleVersion, String replacementNote) {
        return "[REDACTED rule=" + ruleVersion + "] " + replacementNote;
    }

    private void requireOfficer(RequesterRole role) {
        if (role != RequesterRole.REDACTION_OFFICER) {
            throw ApiException.forbidden("role REDACTION_OFFICER required");
        }
    }

    private void requireEvidenceReader(RequesterRole role) {
        if (role != RequesterRole.EVIDENCE_READER) {
            throw ApiException.forbidden("role EVIDENCE_READER required");
        }
    }

    /**
     * 校验全部脱敏附件项（不写入任何数据）：附件存在且属于该观测、规则已登记且未撤销、
     * 同一附件未重复使用同一规则版本、请求内附件不重复、观测存在且非墓碑。
     */
    private void validateRedactItems(String observationId, List<RedactAttachmentsRequest.RedactItem> items) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.notFound("observation already deleted: " + observationId);
        }
        Set<String> seen = new HashSet<>();
        for (RedactAttachmentsRequest.RedactItem item : items) {
            if (!seen.add(item.attachmentId())) {
                throw ApiException.badRequest("duplicate attachment in request: " + item.attachmentId());
            }
            AttachmentRecord attachment = attachmentRepository.findById(item.attachmentId())
                    .orElseThrow(() -> ApiException.notFound("attachment not found: " + item.attachmentId()));
            if (!attachment.observationId().equals(observationId)) {
                throw ApiException.unprocessable(
                        "attachment " + item.attachmentId() + " does not belong to observation " + observationId);
            }
            RedactionRuleRecord rule = ruleRepository.findByRuleVersion(item.ruleVersion())
                    .orElseThrow(() -> ApiException.notFound("redaction rule not found: " + item.ruleVersion()));
            if (!rule.active()) {
                throw ApiException.unprocessable("redaction rule version revoked: " + item.ruleVersion());
            }
            if (redactionRepository.findByAttachmentIdAndRuleVersion(item.attachmentId(), item.ruleVersion())
                    .isPresent()) {
                throw ApiException.unprocessable("rule version " + item.ruleVersion()
                        + " already applied to attachment: " + item.attachmentId());
            }
        }
    }

    /**
     * 将附件项列表序列化为稳定原文（保持请求顺序），作为幂等指纹与重放比对依据。
     */
    private String normalizeItems(List<RedactAttachmentsRequest.RedactItem> items) {
        StringBuilder raw = new StringBuilder();
        for (RedactAttachmentsRequest.RedactItem item : items) {
            raw.append(item.attachmentId()).append(SEPARATOR)
                    .append(item.ruleVersion()).append(SEPARATOR)
                    .append(item.replacementNote()).append(SEPARATOR);
        }
        return raw.toString();
    }

    /**
     * 判断本次脱敏请求参数与已存在的同 redactionId 记录是否完全一致（用于同参重放）。
     */
    private boolean sameRedactionParams(List<AttachmentRedactionRecord> existing, String observationId,
                                        RedactAttachmentsRequest request) {
        if (existing.size() != request.items().size()
                || existing.stream().anyMatch(r -> !r.observationId().equals(observationId))) {
            return false;
        }
        List<RedactAttachmentsRequest.RedactItem> remaining = new ArrayList<>(request.items());
        for (AttachmentRedactionRecord record : existing) {
            boolean matched = remaining.removeIf(item ->
                    item.attachmentId().equals(record.attachmentId())
                            && item.ruleVersion().equals(record.ruleVersion())
                            && item.replacementNote().equals(record.replacementNote()));
            if (!matched) {
                return false;
            }
        }
        return remaining.isEmpty();
    }

    /**
     * 由脱敏版本记录组装响应；附件项顺序与本次请求项顺序保持一致，重放时不改变首次响应顺序。
     */
    private RedactionResponse redactionResponseOf(List<AttachmentRedactionRecord> records,
                                                  List<RedactAttachmentsRequest.RedactItem> requestedItems) {
        java.util.Map<String, AttachmentRedactionRecord> byAttachment = new java.util.HashMap<>();
        for (AttachmentRedactionRecord record : records) {
            byAttachment.put(record.attachmentId(), record);
        }
        List<RedactionResponse.Item> items = requestedItems.stream()
                .map(item -> {
                    AttachmentRedactionRecord r = byAttachment.get(item.attachmentId());
                    if (r == null) {
                        throw new IllegalStateException(
                                "redaction record missing for attachment: " + item.attachmentId());
                    }
                    return new RedactionResponse.Item(r.attachmentId(), r.redactionVersion(), r.ruleVersion(),
                            r.displaySummary(), r.createdAtUtc());
                })
                .toList();
        return new RedactionResponse(records.get(0).redactionId(), records.get(0).observationId(), items);
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> WriteOutcome<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new WriteOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，读取已提交结果：同参重放、异参 409；正常占位返回 null。
     */
    private <T> WriteOutcome<T> insertPlaceholder(String requestId, String fingerprint, String operation,
                                                  Class<T> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new WriteOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    /**
     * 业务成功后回填去重记录响应，与业务变更同事务提交。
     */
    private <T> WriteOutcome<T> complete(String requestId, HttpStatus status, T body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new WriteOutcome<>(status.value(), body);
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

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record WriteOutcome<T>(int status, T body) {
    }
}
