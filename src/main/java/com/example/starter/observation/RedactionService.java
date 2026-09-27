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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 观测附件脱敏业务服务。
 *
 * <p>写操作（登记附件/规则、批量脱敏、撤销、物理清除）均在单事务内完成：先占位写入幂等去重记录，
 * 再加锁完成归属、规则版本、墓碑与权限判定，最后在同一事务提交全部行并回填响应；
 * 任何业务失败整事务回滚，不产生半成品，去重记录也不占键。
 *
 * <p>批量脱敏先校验全部条目（观测墓碑、规则登记/撤销状态、逐附件归属），全部通过后才插入版本；
 * 附件行按附件标识排序加锁，规则行加锁，保证与撤销、并发同参请求按提交顺序串行裁决。
 *
 * <p>脱敏版本只新增受限展示行，不修改原附件摘要、原始观测与既有合并证据；
 * 撤销仅取消公开展示资格，物理清除只置空原始摘要。
 */
@Service
public class RedactionService {

    private static final String SEPARATOR = "";

    private final ObservationRepository observationRepository;
    private final AttachmentRepository attachmentRepository;
    private final RedactionRuleRepository ruleRepository;
    private final RedactionRepository redactionRepository;
    private final EvidenceRepository evidenceRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RedactionService(ObservationRepository observationRepository,
                            AttachmentRepository attachmentRepository,
                            RedactionRuleRepository ruleRepository,
                            RedactionRepository redactionRepository,
                            EvidenceRepository evidenceRepository,
                            RequestLogRepository requestLogRepository,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.observationRepository = observationRepository;
        this.attachmentRepository = attachmentRepository;
        this.ruleRepository = ruleRepository;
        this.redactionRepository = redactionRepository;
        this.evidenceRepository = evidenceRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 写操作 ----------

    /**
     * 登记附件元数据（REDACTOR/EVIDENCE）：附件必须属于一条存在且未删除的观测记录。
     */
    @Transactional
    public RedactionOutcome<AttachmentResponse> registerAttachment(String observationId, ActorRole role,
                                                                   RegisterAttachmentRequest request) {
        requireRole(role, List.of(ActorRole.REDACTOR, ActorRole.EVIDENCE));
        String fingerprint = fingerprint("REGISTER_ATTACHMENT", observationId, request.attachmentId(),
                request.contentSummary(), request.mediaType());
        RedactionOutcome<AttachmentResponse> replayed = checkReplay(request.requestId(), fingerprint,
                AttachmentResponse.class);
        if (replayed != null) {
            return replayed;
        }
        RedactionOutcome<AttachmentResponse> concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "REGISTER_ATTACHMENT", AttachmentResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId,
                        "OBSERVATION_NOT_FOUND"));
        if (current.deleted()) {
            throw ApiException.forbidden("observation already deleted: " + observationId, "OBSERVATION_DELETED");
        }
        if (attachmentRepository.findById(request.attachmentId()).isPresent()) {
            throw ApiException.conflict("attachment already exists: " + request.attachmentId(), null);
        }
        AttachmentRecord record = new AttachmentRecord(request.attachmentId(), observationId,
                request.contentSummary(), request.mediaType(), false, Instant.now(clock), null);
        try {
            attachmentRepository.insert(record);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("attachment already exists: " + request.attachmentId(), null);
        }
        return complete(request.requestId(), HttpStatus.CREATED.value(),
                AttachmentResponse.of(record), AttachmentResponse.class);
    }

    /**
     * 登记脱敏规则版本（ADMIN）：登记后为 ACTIVE；重复版本号返回 409。
     */
    @Transactional
    public RedactionOutcome<RedactionRuleResponse> registerRule(ActorRole role, RegisterRuleRequest request) {
        requireRole(role, List.of(ActorRole.ADMIN));
        String fingerprint = fingerprint("REGISTER_RULE", request.ruleVersion(), request.description());
        RedactionOutcome<RedactionRuleResponse> replayed = checkReplay(request.requestId(), fingerprint,
                RedactionRuleResponse.class);
        if (replayed != null) {
            return replayed;
        }
        RedactionOutcome<RedactionRuleResponse> concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "REGISTER_RULE", RedactionRuleResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        if (ruleRepository.find(request.ruleVersion()).isPresent()) {
            throw ApiException.conflict("rule version already exists: " + request.ruleVersion(), null);
        }
        RedactionRuleRecord record = new RedactionRuleRecord(
                request.ruleVersion(), true, request.description(), Instant.now(clock), null);
        try {
            ruleRepository.insert(record);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("rule version already exists: " + request.ruleVersion(), null);
        }
        return complete(request.requestId(), HttpStatus.CREATED.value(),
                RedactionRuleResponse.of(record), RedactionRuleResponse.class);
    }

    /**
     * 撤销脱敏规则版本（ADMIN）：撤销后不能再用于新脱敏，既有脱敏版本不受影响；重复撤销返回实际状态。
     */
    @Transactional
    public RedactionOutcome<RevokeResultResponse> revokeRule(String ruleVersion, ActorRole role,
                                                             RevokeRuleRequest request) {
        requireRole(role, List.of(ActorRole.ADMIN));
        String fingerprint = fingerprint("REVOKE_RULE", ruleVersion, request.reason());
        RedactionOutcome<RevokeResultResponse> replayed = checkReplay(request.requestId(), fingerprint,
                RevokeResultResponse.class);
        if (replayed != null) {
            return replayed;
        }
        RedactionOutcome<RevokeResultResponse> concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "REVOKE_RULE", RevokeResultResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        RedactionRuleRecord rule = ruleRepository.findForUpdate(ruleVersion)
                .orElseThrow(() -> ApiException.notFound("rule version not found: " + ruleVersion,
                        "RULE_NOT_FOUND"));
        Instant revokedAt;
        if (rule.active()) {
            revokedAt = Instant.now(clock);
            ruleRepository.markRevoked(ruleVersion, revokedAt);
        } else {
            // 已撤销：返回首次撤销的实际时刻，状态与时间语义保持稳定。
            revokedAt = rule.revokedAtUtc();
        }
        RevokeResultResponse body = new RevokeResultResponse(
                "RULE", ruleVersion, "REVOKED", request.requestId(), request.reason(), revokedAt);
        return complete(request.requestId(), HttpStatus.OK.value(), body, RevokeResultResponse.class);
    }

    /**
     * 批量脱敏提交（REDACTOR）：先校验观测、规则与全部条目归属，全部通过后在一个事务内为每个附件生成新版本。
     * 同一附件同一规则版本已存在脱敏版本返回 409；并发同 requestId 同参重放首次完整响应。
     */
    @Transactional
    public RedactionOutcome<RedactionBatchResponse> createRedactions(String observationId, ActorRole role,
                                                                     CreateRedactionRequest request) {
        requireRole(role, List.of(ActorRole.REDACTOR));
        String itemFingerprint = request.items().stream()
                .map(item -> item.attachmentId() + "=" + item.replacementNote())
                .collect(Collectors.joining(","));
        String fingerprint = fingerprint("REDACT", observationId, request.ruleVersion(), itemFingerprint);
        RedactionOutcome<RedactionBatchResponse> replayed = checkReplay(request.requestId(), fingerprint,
                RedactionBatchResponse.class);
        if (replayed != null) {
            return replayed;
        }
        RedactionOutcome<RedactionBatchResponse> concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "REDACT", RedactionBatchResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId,
                        "OBSERVATION_NOT_FOUND"));
        if (current.deleted()) {
            throw ApiException.forbidden("observation already deleted: " + observationId, "OBSERVATION_DELETED");
        }

        RedactionRuleRecord rule = ruleRepository.findForUpdate(request.ruleVersion())
                .orElseThrow(() -> ApiException.unprocessable(
                        "rule version not registered: " + request.ruleVersion(), "RULE_NOT_REGISTERED"));
        if (!rule.active()) {
            throw ApiException.unprocessable(
                    "rule version revoked: " + request.ruleVersion(), "RULE_REVOKED");
        }

        // 一次请求内附件不可重复；重复属于请求语义错误（422），整次不产生版本。
        LinkedHashSet<String> uniqueAttachmentIds = new LinkedHashSet<>();
        for (CreateRedactionRequest.Item item : request.items()) {
            if (!uniqueAttachmentIds.add(item.attachmentId())) {
                throw ApiException.unprocessable(
                        "duplicate attachment in request: " + item.attachmentId(), "DUPLICATE_ATTACHMENT");
            }
        }

        Instant now = Instant.now(clock);
        // 先完成全部条目的归属/版本校验，任一失败直接抛出由事务回滚，绝不先插入部分版本。
        List<CreateRedactionRequest.Item> orderedItems = request.items().stream()
                .sorted(Comparator.comparing(CreateRedactionRequest.Item::attachmentId))
                .toList();
        List<AttachmentRecord> attachments = new ArrayList<>();
        for (CreateRedactionRequest.Item item : orderedItems) {
            AttachmentRecord attachment = attachmentRepository
                    .findForUpdate(observationId, item.attachmentId())
                    .orElseThrow(() -> ApiException.notFound(
                            "attachment not found in observation: " + item.attachmentId(),
                            "ATTACHMENT_NOT_FOUND"));
            if (redactionRepository.findByAttachmentAndRule(item.attachmentId(), request.ruleVersion())
                    .isPresent()) {
                throw ApiException.conflict(
                        "redaction with same rule version already exists for attachment: " + item.attachmentId(),
                        null);
            }
            attachments.add(attachment);
        }

        // 全部校验通过：逐附件生成递增版本并在同一事务提交。
        List<RedactionVersionResponse> versions = new ArrayList<>();
        for (int i = 0; i < orderedItems.size(); i++) {
            CreateRedactionRequest.Item item = orderedItems.get(i);
            int nextVersion = redactionRepository.findMaxVersion(item.attachmentId()).orElse(0) + 1;
            RedactionVersionRecord record = new RedactionVersionRecord(
                    UUID.randomUUID().toString(), observationId, item.attachmentId(), nextVersion,
                    request.ruleVersion(), item.replacementNote(), true, request.requestId(), now, null, null);
            try {
                redactionRepository.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发下唯一键串行化：(附件, 规则版本) 只允许一个版本，后到者按 409 失败且不产生半成品。
                throw ApiException.conflict(
                        "redaction with same rule version already exists for attachment: " + item.attachmentId(),
                        null);
            }
            versions.add(RedactionVersionResponse.of(record));
        }

        RedactionBatchResponse body = new RedactionBatchResponse(
                observationId, request.requestId(), request.ruleVersion(),
                request.items().size(), versions.size(), List.copyOf(versions));
        return complete(request.requestId(), HttpStatus.OK.value(), body, RedactionBatchResponse.class);
    }

    /**
     * 撤销脱敏版本公开展示资格（REDACTOR/ADMIN）：版本、替换说明与撤销时刻保留；重复撤销返回首次撤销实际状态。
     */
    @Transactional
    public RedactionOutcome<RevokeResultResponse> revokeRedaction(String redactionId, ActorRole role,
                                                                  RevokeRedactionRequest request) {
        requireRole(role, List.of(ActorRole.REDACTOR, ActorRole.ADMIN));
        String fingerprint = fingerprint("REVOKE_REDACTION", redactionId, request.reason());
        RedactionOutcome<RevokeResultResponse> replayed = checkReplay(request.requestId(), fingerprint,
                RevokeResultResponse.class);
        if (replayed != null) {
            return replayed;
        }
        RedactionOutcome<RevokeResultResponse> concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "REVOKE_REDACTION", RevokeResultResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        RedactionVersionRecord version = redactionRepository.findByIdForUpdate(redactionId)
                .orElseThrow(() -> ApiException.notFound("redaction not found: " + redactionId,
                        "REDACTION_NOT_FOUND"));
        Instant revokedAt;
        String reason;
        if (version.active()) {
            revokedAt = Instant.now(clock);
            reason = request.reason();
            redactionRepository.markRevoked(redactionId, revokedAt, reason);
        } else {
            // 已撤销：返回首次撤销的实际时刻与理由，重复撤销不覆盖历史。
            revokedAt = version.revokedAtUtc();
            reason = version.revokeReason();
        }
        RevokeResultResponse body = new RevokeResultResponse(
                "REDACTION", redactionId, "REVOKED", request.requestId(), reason, revokedAt);
        return complete(request.requestId(), HttpStatus.OK.value(), body, RevokeResultResponse.class);
    }

    /**
     * 物理清除附件原始内容（ADMIN）：仅置空摘要并记录清除时刻，元数据与脱敏版本保留；重复清除返回实际状态。
     */
    @Transactional
    public RedactionOutcome<AttachmentResponse> purgeAttachment(String observationId, String attachmentId,
                                                                ActorRole role, PurgeAttachmentRequest request) {
        requireRole(role, List.of(ActorRole.ADMIN));
        String fingerprint = fingerprint("PURGE_ATTACHMENT", observationId, attachmentId, request.reason());
        RedactionOutcome<AttachmentResponse> replayed = checkReplay(request.requestId(), fingerprint,
                AttachmentResponse.class);
        if (replayed != null) {
            return replayed;
        }
        RedactionOutcome<AttachmentResponse> concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "PURGE_ATTACHMENT", AttachmentResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        AttachmentRecord attachment = requireAttachment(observationId, attachmentId);
        if (!attachment.purged()) {
            Instant purgedAt = Instant.now(clock);
            attachmentRepository.markPurged(attachmentId, purgedAt);
            attachment = new AttachmentRecord(attachmentId, observationId, null, attachment.mediaType(),
                    true, attachment.createdAtUtc(), purgedAt);
        }
        return complete(request.requestId(), HttpStatus.OK.value(),
                AttachmentResponse.of(attachment), AttachmentResponse.class);
    }

    // ---------- 读操作 ----------

    /**
     * 公开/证据视图：列出观测下附件的展示视图。
     * PUBLIC/REDACTOR 仅见每个附件最新有效脱敏版本（无则回退原始摘要）；墓碑观测对无证据权限者按 404 处理。
     */
    @Transactional(readOnly = true)
    public List<AttachmentViewResponse> listAttachmentViews(String observationId, ActorRole role) {
        ObservationSnapshot current = requireVisibleObservation(observationId, role);
        List<AttachmentRecord> attachments = attachmentRepository.findByObservation(observationId);
        if (role == ActorRole.EVIDENCE) {
            return attachments.stream().map(this::evidenceViewOf).toList();
        }
        return attachments.stream().map(this::publicViewOf).toList();
    }

    /**
     * 单个附件展示视图（公开）。
     */
    @Transactional(readOnly = true)
    public AttachmentViewResponse getAttachmentView(String observationId, String attachmentId, ActorRole role) {
        requireVisibleObservation(observationId, role);
        AttachmentRecord attachment = requireAttachment(observationId, attachmentId);
        if (role == ActorRole.EVIDENCE) {
            return evidenceViewOf(attachment);
        }
        return publicViewOf(attachment);
    }

    /**
     * 证据权限按版本读取：version=0 读取原始摘要（物理清除后为 null），version≥1 读取指定脱敏版本（含已撤销）。
     */
    @Transactional(readOnly = true)
    public AttachmentViewResponse getEvidenceVersion(String observationId, String attachmentId,
                                                     int version, ActorRole role) {
        requireRole(role, List.of(ActorRole.EVIDENCE));
        requireObservationExists(observationId);
        AttachmentRecord attachment = requireAttachment(observationId, attachmentId);
        if (version == 0) {
            return AttachmentViewResponse.original(attachment);
        }
        RedactionVersionRecord record = redactionRepository
                .findByAttachment(attachmentId).stream()
                .filter(candidate -> candidate.version() == version)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound(
                        "redaction version not found: " + attachmentId + "@v" + version,
                        "REDACTION_VERSION_NOT_FOUND"));
        return AttachmentViewResponse.redacted(attachment, record);
    }

    /**
     * 明细查询：观测下全部脱敏版本（含已撤销），按附件标识、版本号升序（REDACTOR/EVIDENCE）。
     */
    @Transactional(readOnly = true)
    public List<RedactionVersionResponse> listRedactions(String observationId, ActorRole role) {
        requireRole(role, List.of(ActorRole.REDACTOR, ActorRole.EVIDENCE));
        requireObservationExists(observationId);
        return redactionRepository.findByObservation(observationId).stream()
                .map(RedactionVersionResponse::of).toList();
    }

    /**
     * 历史查询：按 UTC 左闭右开区间 [from, to) 过滤脱敏版本生成时刻；attachmentId 非空时限定单附件。
     */
    @Transactional(readOnly = true)
    public List<RedactionVersionResponse> findRedactionHistory(String observationId, String attachmentId,
                                                               Instant from, Instant to, ActorRole role) {
        requireRole(role, List.of(ActorRole.REDACTOR, ActorRole.EVIDENCE));
        requireObservationExists(observationId);
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("time range must satisfy from < to (half-open [from, to) in UTC)");
        }
        List<RedactionVersionRecord> records = attachmentId == null
                ? redactionRepository.findByObservationBetween(observationId, from, to)
                : redactionRepository.findByAttachmentBetween(attachmentId, from, to).stream()
                        .filter(record -> record.observationId().equals(observationId))
                        .toList();
        if (attachmentId != null) {
            requireAttachment(observationId, attachmentId);
        }
        return records.stream().map(RedactionVersionResponse::of).toList();
    }

    /**
     * 诊断查询：返回附件、脱敏版本的实际数量与逐附件差额（最新有效版本号），只读不改状态。
     */
    @Transactional(readOnly = true)
    public RedactionDiagnosticsResponse getDiagnostics(String observationId, ActorRole role) {
        requireRole(role, List.of(ActorRole.REDACTOR, ActorRole.EVIDENCE));
        requireObservationExists(observationId);
        List<AttachmentRecord> attachments = attachmentRepository.findByObservation(observationId);
        List<RedactionVersionRecord> allVersions = redactionRepository.findByObservation(observationId);
        Map<String, List<RedactionVersionRecord>> byAttachment = allVersions.stream()
                .collect(Collectors.groupingBy(RedactionVersionRecord::attachmentId));
        int active = 0;
        int revoked = 0;
        List<RedactionDiagnosticsResponse.Item> items = new ArrayList<>();
        for (AttachmentRecord attachment : attachments) {
            List<RedactionVersionRecord> versions = byAttachment
                    .getOrDefault(attachment.attachmentId(), List.of());
            int itemActive = (int) versions.stream().filter(RedactionVersionRecord::active).count();
            int itemRevoked = versions.size() - itemActive;
            active += itemActive;
            revoked += itemRevoked;
            Integer latestActive = versions.stream()
                    .filter(RedactionVersionRecord::active)
                    .map(RedactionVersionRecord::version)
                    .max(Integer::compareTo)
                    .orElse(null);
            items.add(new RedactionDiagnosticsResponse.Item(
                    attachment.attachmentId(), attachment.purged(), !attachment.purged(),
                    versions.size(), itemActive, itemRevoked, latestActive));
        }
        int purged = (int) attachments.stream().filter(AttachmentRecord::purged).count();
        return new RedactionDiagnosticsResponse(observationId, attachments.size(), purged,
                allVersions.size(), active, revoked, List.copyOf(items));
    }

    /**
     * 查询某观测版本固化的合并/人工选择证据（EVIDENCE）；后续脱敏不改变这些引用。
     */
    @Transactional(readOnly = true)
    public List<EvidenceResponse> getEvidence(String observationId, int observationVersion, ActorRole role) {
        requireRole(role, List.of(ActorRole.EVIDENCE));
        ObservationSnapshot snapshot = observationRepository.findVersion(observationId, observationVersion)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + observationVersion,
                        "OBSERVATION_VERSION_NOT_FOUND"));
        if (snapshot.deleted()) {
            throw ApiException.notFound("version is a tombstone: " + observationId + "@" + observationVersion,
                    "TOMBSTONE_VERSION");
        }
        return evidenceRepository.findByVersion(observationId, observationVersion).stream()
                .map(EvidenceResponse::of).toList();
    }

    /**
     * 查询单个规则版本登记状态（ADMIN/REDACTOR/EVIDENCE 可读）。
     */
    @Transactional(readOnly = true)
    public RedactionRuleResponse getRule(String ruleVersion, ActorRole role) {
        requireRole(role, List.of(ActorRole.ADMIN, ActorRole.REDACTOR, ActorRole.EVIDENCE));
        RedactionRuleRecord rule = ruleRepository.find(ruleVersion)
                .orElseThrow(() -> ApiException.notFound("rule version not found: " + ruleVersion,
                        "RULE_NOT_FOUND"));
        return RedactionRuleResponse.of(rule);
    }

    // ---------- 证据固化（供 ObservationService 在合并/解决生成新版本时同事务调用） ----------

    /**
     * 固化观测新版本引用的附件版本：有最新有效脱敏版本则固化该版本，否则固化原始摘要引用。
     * 必须在生成新版本的同一事务内调用；读取的是该事务提交瞬间已提交的脱敏状态。
     */
    @Transactional
    public void fixEvidenceForNewVersion(String observationId, int observationVersion) {
        Instant now = Instant.now(clock);
        for (AttachmentRecord attachment : attachmentRepository.findByObservation(observationId)) {
            Optional<RedactionVersionRecord> latestActive =
                    redactionRepository.findLatestActive(attachment.attachmentId());
            EvidenceRecord record;
            if (latestActive.isPresent()) {
                RedactionVersionRecord version = latestActive.get();
                record = new EvidenceRecord(observationId, observationVersion, attachment.attachmentId(),
                        false, version.redactionId(), version.version(), version.ruleVersion(), now);
            } else {
                record = new EvidenceRecord(observationId, observationVersion, attachment.attachmentId(),
                        true, null, null, null, now);
            }
            evidenceRepository.insert(record);
        }
    }

    // ---------- 辅助方法 ----------

    private void requireRole(ActorRole role, List<ActorRole> allowed) {
        if (!allowed.contains(role)) {
            throw ApiException.forbidden("role not allowed: " + role, "FORBIDDEN_ROLE");
        }
    }

    private ObservationSnapshot requireVisibleObservation(String observationId, ActorRole role) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId,
                        "OBSERVATION_NOT_FOUND"));
        if (current.deleted() && role != ActorRole.EVIDENCE) {
            throw ApiException.notFound("observation not found: " + observationId, "OBSERVATION_NOT_FOUND");
        }
        return current;
    }

    private void requireObservationExists(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId,
                        "OBSERVATION_NOT_FOUND"));
    }

    private AttachmentRecord requireAttachment(String observationId, String attachmentId) {
        AttachmentRecord attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound("attachment not found: " + attachmentId,
                        "ATTACHMENT_NOT_FOUND"));
        if (!attachment.observationId().equals(observationId)) {
            throw ApiException.notFound("attachment not found in observation: " + attachmentId,
                    "ATTACHMENT_NOT_FOUND");
        }
        return attachment;
    }

    private AttachmentViewResponse publicViewOf(AttachmentRecord attachment) {
        return redactionRepository.findLatestActive(attachment.attachmentId())
                .map(version -> AttachmentViewResponse.redacted(attachment, version))
                .orElseGet(() -> AttachmentViewResponse.original(attachment));
    }

    private AttachmentViewResponse evidenceViewOf(AttachmentRecord attachment) {
        // 证据角色的列表视图同样展示最新有效脱敏版本，原始摘要经按版本（version=0）接口读取。
        return publicViewOf(attachment);
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> RedactionOutcome<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new RedactionOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：同参重放、异参 409。
     */
    private <T> RedactionOutcome<T> insertPlaceholder(String requestId, String fingerprint,
                                                      String operation, Class<T> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new RedactionOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    private <T> RedactionOutcome<T> complete(String requestId, int status, T body, Class<T> bodyType) {
        requestLogRepository.complete(requestId, status, writeBody(body));
        return new RedactionOutcome<>(status, body);
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
    public record RedactionOutcome<T>(int status, T body) {
    }
}
