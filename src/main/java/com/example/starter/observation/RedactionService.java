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
import java.util.Optional;
import java.util.Set;

/**
 * 观测附件脱敏业务服务。
 *
 * <p>写操作（登记附件/规则、脱敏、撤销、物理清除）均复用 request_log 幂等去重：
 * 事务内先占位，再对 observation_current 行加锁（SELECT ... FOR UPDATE）完成全部校验与变更，
 * 最后回填响应并整体提交；任何业务失败都回滚，不占键。同一观测的写操作按行锁串行，
 * 与既有合并/删除/解决按提交顺序裁决。
 *
 * <p>脱敏只新增受限展示版本（redaction_version），不修改原始附件摘要、原始观测与既有合并证据。
 * 一次请求含多附件时，先校验全部归属、规则版本与权限，任一失败整次不产生脱敏版本。
 * 撤销只翻转公开展示资格，保留版本、理由与撤销时刻。物理清除清空原始摘要且不可恢复。
 */
@Service
public class RedactionService {

    private static final String SEPARATOR = "";

    private final ObservationRepository observationRepository;
    private final AttachmentRepository attachmentRepository;
    private final RedactionRuleRepository ruleRepository;
    private final RedactionVersionRepository redactionRepository;
    private final VersionAttachmentRepository versionAttachmentRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RedactionService(ObservationRepository observationRepository,
                            AttachmentRepository attachmentRepository,
                            RedactionRuleRepository ruleRepository,
                            RedactionVersionRepository redactionRepository,
                            VersionAttachmentRepository versionAttachmentRepository,
                            RequestLogRepository requestLogRepository,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.observationRepository = observationRepository;
        this.attachmentRepository = attachmentRepository;
        this.ruleRepository = ruleRepository;
        this.redactionRepository = redactionRepository;
        this.versionAttachmentRepository = versionAttachmentRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 登记附件 ----------

    /**
     * 登记观测附件元数据。附件必须挂在存在的非墓碑观测上；归属与原始摘要登记后不可修改。
     */
    @Transactional
    public OpOutcome<AttachmentResponse> registerAttachment(String observationId,
                                                            RegisterAttachmentRequest request) {
        requireRole(request.role(), ActorRole::hasRedactionWriteAccess, "register attachment");
        String fingerprint = fingerprint("REGISTER_ATTACHMENT", observationId, request.attachmentId(),
                request.filename(), request.mediaType(), request.contentSummary());
        AttachmentResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                "REGISTER_ATTACHMENT", AttachmentResponse.class);
        if (replayed != null) {
            return new OpOutcome<>(HttpStatus.CREATED.value(), replayed);
        }

        ObservationSnapshot current = lockObservation(observationId);
        if (current.deleted()) {
            throw ApiException.notFound("OBSERVATION_TOMBSTONE",
                    "observation already deleted: " + observationId);
        }
        if (attachmentRepository.find(request.attachmentId()).isPresent()) {
            throw ApiException.conflict("ATTACHMENT_EXISTS",
                    "attachment already exists: " + request.attachmentId(), null);
        }
        AttachmentRecord record = new AttachmentRecord(request.attachmentId(), observationId,
                request.filename(), request.mediaType(), request.contentSummary(),
                sha256(request.contentSummary()), false, null, Instant.now(clock));
        try {
            attachmentRepository.insert(record);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ATTACHMENT_EXISTS",
                    "attachment already exists: " + request.attachmentId(), null);
        }
        AttachmentResponse body = AttachmentResponse.of(record);
        complete(request.requestId(), HttpStatus.CREATED.value(), body);
        return new OpOutcome<>(HttpStatus.CREATED.value(), body);
    }

    // ---------- 登记 / 撤销规则 ----------

    /**
     * 登记脱敏规则版本（状态 ACTIVE）。规则版本登记后才可用于脱敏。
     */
    @Transactional
    public OpOutcome<RuleResponse> registerRule(RegisterRuleRequest request) {
        requireRole(request.role(), ActorRole::hasRedactionWriteAccess, "register rule");
        String normalizedTerms = writeTermsJson(normalizeTerms(request.maskTerms()));
        String fingerprint = fingerprint("REGISTER_RULE", request.ruleVersion(), request.description(),
                normalizedTerms, request.replacementToken());
        RuleResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                "REGISTER_RULE", RuleResponse.class);
        if (replayed != null) {
            return new OpOutcome<>(HttpStatus.CREATED.value(), replayed);
        }

        if (ruleRepository.find(request.ruleVersion()).isPresent()) {
            throw ApiException.conflict("RULE_EXISTS", "rule already registered: " + request.ruleVersion(), null);
        }
        RedactionRuleRecord record = new RedactionRuleRecord(request.ruleVersion(), request.description(),
                normalizeTerms(request.maskTerms()), request.replacementToken(), RuleStatus.ACTIVE,
                Instant.now(clock), null, null);
        try {
            ruleRepository.insert(record);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("RULE_EXISTS", "rule already registered: " + request.ruleVersion(), null);
        }
        RuleResponse body = ruleResponse(record);
        complete(request.requestId(), HttpStatus.CREATED.value(), body);
        return new OpOutcome<>(HttpStatus.CREATED.value(), body);
    }

    /**
     * 撤销脱敏规则版本：不可逆；撤销后该规则不得再用于新脱敏，既有脱敏版本不受影响。
     */
    @Transactional
    public OpOutcome<RuleResponse> revokeRule(String ruleVersion, RevokeRuleRequest request) {
        requireRole(request.role(), ActorRole::hasRedactionWriteAccess, "revoke rule");
        String fingerprint = fingerprint("REVOKE_RULE", ruleVersion, request.operator(), request.reason());
        RuleResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                "REVOKE_RULE", RuleResponse.class);
        if (replayed != null) {
            return new OpOutcome<>(HttpStatus.OK.value(), replayed);
        }

        RedactionRuleRecord rule = ruleRepository.findForUpdate(ruleVersion)
                .orElseThrow(() -> ApiException.notFound("RULE_NOT_FOUND", "rule not found: " + ruleVersion));
        if (rule.status() == RuleStatus.REVOKED) {
            throw ApiException.unprocessable("RULE_REVOKED", "rule already revoked: " + ruleVersion);
        }
        Instant now = Instant.now(clock);
        ruleRepository.revoke(ruleVersion, now, request.reason());
        RuleResponse body = ruleResponse(new RedactionRuleRecord(rule.ruleVersion(), rule.description(),
                rule.maskTerms(), rule.replacementToken(), RuleStatus.REVOKED, rule.registeredAtUtc(),
                now, request.reason()));
        complete(request.requestId(), HttpStatus.OK.value(), body);
        return new OpOutcome<>(HttpStatus.OK.value(), body);
    }

    /**
     * 读取规则版本明细（证据权限）。
     */
    @Transactional(readOnly = true)
    public RuleResponse getRule(String ruleVersion, String role) {
        requireRole(role, ActorRole::hasEvidenceAccess, "read rule");
        RedactionRuleRecord rule = ruleRepository.find(ruleVersion)
                .orElseThrow(() -> ApiException.notFound("RULE_NOT_FOUND", "rule not found: " + ruleVersion));
        return ruleResponse(rule);
    }

    // ---------- 脱敏 ----------

    /**
     * 提交附件脱敏：先校验全部附件归属、规则版本与权限，任一失败整次不产生脱敏版本。
     * 成功时为每个附件生成递增的受限展示版本，原始摘要与既有证据不修改。
     */
    @Transactional
    public OpOutcome<RedactOutcomeResponse> redact(String observationId, RedactAttachmentsRequest request) {
        requireRole(request.role(), ActorRole::hasRedactionWriteAccess, "redact attachments");
        String fingerprint = fingerprint("REDACT", observationId, request.operator(), request.ruleVersion(),
                itemsFingerprint(request.items()));
        RedactOutcomeResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                "REDACT", RedactOutcomeResponse.class);
        if (replayed != null) {
            return new OpOutcome<>(HttpStatus.CREATED.value(), replayed);
        }

        // 同一附件在一次请求中不得重复
        Set<String> seen = new HashSet<>();
        for (RedactAttachmentsRequest.Item item : request.items()) {
            if (!seen.add(item.attachmentId())) {
                throw ApiException.badRequest("DUPLICATE_ATTACHMENT_IN_REQUEST",
                        "duplicate attachment in request: " + item.attachmentId());
            }
        }

        ObservationSnapshot current = lockObservation(observationId);
        if (current.deleted()) {
            throw ApiException.notFound("OBSERVATION_TOMBSTONE",
                    "observation already deleted: " + observationId);
        }

        // 锁定规则行：与规则撤销按提交顺序裁决——本事务先提交则版本有效，撤销须等待本事务结束
        RedactionRuleRecord rule = ruleRepository.findForUpdate(request.ruleVersion())
                .orElseThrow(() -> ApiException.notFound("RULE_NOT_FOUND",
                        "rule not found: " + request.ruleVersion()));
        if (rule.status() == RuleStatus.REVOKED) {
            throw ApiException.unprocessable("RULE_REVOKED",
                    "rule version revoked: " + request.ruleVersion());
        }

        // 先校验全部附件归属与状态，任一失败整次不产生脱敏版本
        List<AttachmentRecord> attachments = new ArrayList<>();
        for (RedactAttachmentsRequest.Item item : request.items()) {
            AttachmentRecord attachment = attachmentRepository.find(item.attachmentId())
                    .orElseThrow(() -> ApiException.notFound("ATTACHMENT_NOT_FOUND",
                            "attachment not found: " + item.attachmentId()));
            if (!attachment.observationId().equals(observationId)) {
                throw ApiException.unprocessable("ATTACHMENT_OWNERSHIP",
                        "attachment does not belong to observation " + observationId + ": "
                                + item.attachmentId());
            }
            if (attachment.contentPurged()) {
                throw ApiException.unprocessable("CONTENT_PURGED",
                        "attachment content already purged: " + item.attachmentId());
            }
            if (redactionRepository.findByAttachmentAndRule(item.attachmentId(), request.ruleVersion())
                    .isPresent()) {
                throw ApiException.conflict("REDACTION_RULE_ALREADY_APPLIED",
                        "attachment already redacted with rule " + request.ruleVersion() + ": "
                                + item.attachmentId(), null);
            }
            attachments.add(attachment);
        }

        // 全部校验通过：在同一事务内为每个附件生成递增脱敏版本
        Instant now = Instant.now(clock);
        List<RedactionVersionResponse> versions = new ArrayList<>();
        for (int i = 0; i < attachments.size(); i++) {
            AttachmentRecord attachment = attachments.get(i);
            String replacementNote = request.items().get(i).replacementNote();
            int nextVersion = redactionRepository.maxVersion(attachment.attachmentId()) + 1;
            String redactedSummary = applyRule(attachment.contentSummary(), rule);
            RedactionVersionRecord record = new RedactionVersionRecord(attachment.attachmentId(),
                    observationId, nextVersion, request.ruleVersion(), redactedSummary, replacementNote,
                    RedactionStatus.ACTIVE, request.requestId(), request.operator(), now,
                    null, null, null, null);
            try {
                redactionRepository.insert(record);
            } catch (DuplicateKeyException e) {
                // 并发下 (attachment, ruleVersion) 已被占用：整次回滚，由先到者生效
                throw ApiException.conflict("REDACTION_RULE_ALREADY_APPLIED",
                        "attachment already redacted with rule " + request.ruleVersion() + ": "
                                + attachment.attachmentId(), null);
            }
            versions.add(RedactionVersionResponse.of(record));
        }

        RedactOutcomeResponse body = new RedactOutcomeResponse(observationId, request.ruleVersion(),
                request.items().size(), versions.size(), versions);
        complete(request.requestId(), HttpStatus.CREATED.value(), body);
        return new OpOutcome<>(HttpStatus.CREATED.value(), body);
    }

    // ---------- 撤销脱敏 ----------

    /**
     * 撤销脱敏版本公开展示资格：保留脱敏版本、理由与撤销时刻，不删除版本行。
     */
    @Transactional
    public OpOutcome<RedactionVersionResponse> revokeRedaction(String attachmentId, int version,
                                                               RevokeRedactionRequest request) {
        requireRole(request.role(), ActorRole::hasRedactionWriteAccess, "revoke redaction");
        String fingerprint = fingerprint("REVOKE_REDACTION", attachmentId, String.valueOf(version),
                request.operator(), request.reason());
        RedactionVersionResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                "REVOKE_REDACTION", RedactionVersionResponse.class);
        if (replayed != null) {
            return new OpOutcome<>(HttpStatus.OK.value(), replayed);
        }

        RedactionVersionRecord located = redactionRepository.find(attachmentId, version)
                .orElseThrow(() -> ApiException.notFound("REDACTION_NOT_FOUND",
                        "redaction not found: " + attachmentId + "@" + version));
        // 通过观测行锁与脱敏/合并/删除按提交顺序裁决；取锁后必须重读状态，避免并发撤销覆盖
        lockObservation(located.observationId());
        RedactionVersionRecord record = redactionRepository.find(attachmentId, version)
                .orElseThrow(() -> ApiException.notFound("REDACTION_NOT_FOUND",
                        "redaction not found: " + attachmentId + "@" + version));
        if (record.status() == RedactionStatus.REVOKED) {
            throw ApiException.unprocessable("REDACTION_ALREADY_REVOKED",
                    "redaction already revoked: " + attachmentId + "@" + version);
        }
        Instant now = Instant.now(clock);
        redactionRepository.revoke(attachmentId, version, now, request.reason(),
                request.operator(), request.requestId());
        RedactionVersionResponse body = RedactionVersionResponse.of(new RedactionVersionRecord(
                record.attachmentId(), record.observationId(), record.version(), record.ruleVersion(),
                record.redactedSummary(), record.replacementNote(), RedactionStatus.REVOKED,
                record.requestId(), record.operator(), record.createdAtUtc(), now, request.reason(),
                request.operator(), request.requestId()));
        complete(request.requestId(), HttpStatus.OK.value(), body);
        return new OpOutcome<>(HttpStatus.OK.value(), body);
    }

    // ---------- 物理清除 ----------

    /**
     * 物理清除附件内容（合规处置）：清空原始摘要且不可恢复；既有脱敏版本与历史证据引用不受影响。
     */
    @Transactional
    public OpOutcome<AttachmentResponse> purgeAttachment(String attachmentId, PurgeAttachmentRequest request) {
        requireRole(request.role(), ActorRole::hasPurgeAccess, "purge attachment");
        String fingerprint = fingerprint("PURGE", attachmentId, request.operator(), request.reason());
        AttachmentResponse replayed = replayIfPresent(request.requestId(), fingerprint,
                "PURGE", AttachmentResponse.class);
        if (replayed != null) {
            return new OpOutcome<>(HttpStatus.OK.value(), replayed);
        }

        AttachmentRecord existing = attachmentRepository.find(attachmentId)
                .orElseThrow(() -> ApiException.notFound("ATTACHMENT_NOT_FOUND",
                        "attachment not found: " + attachmentId));
        lockObservation(existing.observationId());
        AttachmentRecord current = attachmentRepository.findForUpdate(attachmentId)
                .orElseThrow(() -> ApiException.notFound("ATTACHMENT_NOT_FOUND",
                        "attachment not found: " + attachmentId));
        if (current.contentPurged()) {
            throw ApiException.unprocessable("CONTENT_PURGED",
                    "attachment content already purged: " + attachmentId);
        }
        Instant now = Instant.now(clock);
        attachmentRepository.purgeContent(attachmentId, now);
        AttachmentResponse body = AttachmentResponse.of(new AttachmentRecord(current.attachmentId(),
                current.observationId(), current.filename(), current.mediaType(), null,
                current.contentDigest(), true, now, current.createdAtUtc()));
        complete(request.requestId(), HttpStatus.OK.value(), body);
        return new OpOutcome<>(HttpStatus.OK.value(), body);
    }

    // ---------- 查询（只读，不改变状态） ----------

    /**
     * 公开查询：只返回每个附件最新有效（ACTIVE）脱敏版本的受限展示内容，不暴露原始摘要。
     */
    @Transactional(readOnly = true)
    public List<PublicAttachmentResponse> getPublicAttachments(String observationId) {
        ObservationSnapshot current = observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        // 已删除墓碑观测不再公开其附件受限展示内容
        if (current.deleted()) {
            throw ApiException.notFound("OBSERVATION_TOMBSTONE",
                    "observation already deleted: " + observationId);
        }
        List<PublicAttachmentResponse> result = new ArrayList<>();
        for (AttachmentRecord attachment : attachmentRepository.findByObservationId(observationId)) {
            redactionRepository.findLatestActive(attachment.attachmentId())
                    .ifPresent(record -> result.add(PublicAttachmentResponse.of(record)));
        }
        return result;
    }

    /**
     * 证据权限：读取附件原始明细（含原始摘要或清除状态）。
     */
    @Transactional(readOnly = true)
    public AttachmentResponse getAttachmentEvidence(String attachmentId, String role) {
        requireRole(role, ActorRole::hasEvidenceAccess, "read attachment evidence");
        AttachmentRecord record = attachmentRepository.find(attachmentId)
                .orElseThrow(() -> ApiException.notFound("ATTACHMENT_NOT_FOUND",
                        "attachment not found: " + attachmentId));
        return AttachmentResponse.of(record);
    }

    /**
     * 证据权限：读取附件全部脱敏版本历史（按版本升序，含已撤销）。
     */
    @Transactional(readOnly = true)
    public List<RedactionVersionResponse> getAttachmentHistory(String attachmentId, String role) {
        requireRole(role, ActorRole::hasEvidenceAccess, "read attachment history");
        attachmentRepository.find(attachmentId)
                .orElseThrow(() -> ApiException.notFound("ATTACHMENT_NOT_FOUND",
                        "attachment not found: " + attachmentId));
        return redactionRepository.findByAttachment(attachmentId).stream()
                .map(RedactionVersionResponse::of)
                .toList();
    }

    /**
     * 证据权限：按版本读取脱敏摘要明细。
     */
    @Transactional(readOnly = true)
    public RedactionVersionResponse getRedactionVersion(String attachmentId, int version, String role) {
        requireRole(role, ActorRole::hasEvidenceAccess, "read redaction version");
        RedactionVersionRecord record = redactionRepository.find(attachmentId, version)
                .orElseThrow(() -> ApiException.notFound("REDACTION_NOT_FOUND",
                        "redaction not found: " + attachmentId + "@" + version));
        return RedactionVersionResponse.of(record);
    }

    /**
     * 证据权限：读取观测版本固化的附件证据引用（后续脱敏/撤销不追溯替换）。
     */
    @Transactional(readOnly = true)
    public List<VersionAttachmentRefResponse> getVersionAttachmentRefs(String observationId, int version,
                                                                       String role) {
        requireRole(role, ActorRole::hasEvidenceAccess, "read version attachment refs");
        observationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        List<VersionAttachmentRefResponse> result = new ArrayList<>();
        for (VersionAttachmentRef ref : versionAttachmentRepository.findByVersion(observationId, version)) {
            result.add(new VersionAttachmentRefResponse(ref.observationId(), ref.version(),
                    ref.attachmentId(), ref.redactionVersion(), resolveRefSummary(ref), ref.createdAtUtc()));
        }
        return result;
    }

    /**
     * 证据权限：脱敏诊断查询。时间区间按左闭右开 [from, to) 解释；只读不改变状态。
     */
    @Transactional(readOnly = true)
    public RedactionDiagnosticsResponse getDiagnostics(String observationId, Instant from, Instant to,
                                                       Integer expectedCount, String role) {
        requireRole(role, ActorRole::hasEvidenceAccess, "read diagnostics");
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("INVALID_RANGE", "from must be before to (left-closed, right-open)");
        }
        List<RedactionVersionRecord> records = redactionRepository.findByObservation(observationId, from, to);
        int actualCount = records.size();
        Integer difference = expectedCount == null ? null : actualCount - expectedCount;
        List<RedactionVersionResponse> versions = records.stream()
                .map(RedactionVersionResponse::of)
                .toList();
        return new RedactionDiagnosticsResponse(observationId, from, to, actualCount, expectedCount,
                difference,
                redactionRepository.countByObservationAndStatus(observationId, RedactionStatus.ACTIVE),
                redactionRepository.countByObservationAndStatus(observationId, RedactionStatus.REVOKED),
                versions);
    }

    // ---------- 内部辅助 ----------

    /**
     * 对观测当前行加锁并返回快照；不存在返回 404。
     */
    private ObservationSnapshot lockObservation(String observationId) {
        return observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
    }

    /**
     * 角色校验：不具备权限时抛 403。
     */
    private void requireRole(String role, java.util.function.Predicate<ActorRole> allowed, String action) {
        ActorRole actorRole = ActorRole.fromValue(role);
        if (!allowed.test(actorRole)) {
            throw ApiException.forbidden("role not permitted to " + action + ": " + actorRole);
        }
    }

    /**
     * 应用脱敏规则：对摘要中命中的敏感词条（不区分大小写）整体替换为替换标记。
     * 词条按长度降序处理，保证结果确定。
     */
    private String applyRule(String contentSummary, RedactionRuleRecord rule) {
        String result = contentSummary;
        List<String> terms = new ArrayList<>(rule.maskTerms());
        terms.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String term : terms) {
            result = replaceCaseInsensitive(result, term, rule.replacementToken());
        }
        return result;
    }

    private String replaceCaseInsensitive(String text, String term, String replacement) {
        if (term.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        String lower = text.toLowerCase();
        String lowerTerm = term.toLowerCase();
        int index = 0;
        while (true) {
            int hit = lower.indexOf(lowerTerm, index);
            if (hit < 0) {
                out.append(text, index, text.length());
                break;
            }
            out.append(text, index, hit).append(replacement);
            index = hit + term.length();
        }
        return out.toString();
    }

    /**
     * 解析固化引用对应的摘要：redactionVersion 为 null 取原始摘要（已清除则为 null），
     * 否则取对应脱敏版本的脱敏后摘要。
     */
    private String resolveRefSummary(VersionAttachmentRef ref) {
        if (ref.redactionVersion() == null) {
            return attachmentRepository.find(ref.attachmentId())
                    .map(AttachmentRecord::contentSummary)
                    .orElse(null);
        }
        return redactionRepository.find(ref.attachmentId(), ref.redactionVersion())
                .map(RedactionVersionRecord::redactedSummary)
                .orElse(null);
    }

    private RuleResponse ruleResponse(RedactionRuleRecord record) {
        return new RuleResponse(record.ruleVersion(), record.description(),
                writeTermsJson(record.maskTerms()), record.replacementToken(), record.status(),
                record.registeredAtUtc(), record.revokedAtUtc(), record.revokeReason());
    }

    private List<String> normalizeTerms(List<String> terms) {
        return terms == null ? List.of() : List.copyOf(terms);
    }

    private String writeTermsJson(List<String> terms) {
        try {
            return objectMapper.writeValueAsString(terms);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize mask terms", e);
        }
    }

    private String itemsFingerprint(List<RedactAttachmentsRequest.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (RedactAttachmentsRequest.Item item : items) {
            sb.append(SEPARATOR).append(item.attachmentId())
                    .append(SEPARATOR).append(item.replacementNote());
        }
        return sb.toString();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 幂等占位：无记录时插入占位并返回 null（继续执行）；同键同参返回已存响应体重放；
     * 同键异参抛 409；并发同键时读取已提交结果。业务失败时占位随事务回滚，不占键。
     */
    private <T> T replayIfPresent(String requestId, String fingerprint, String operation, Class<T> type) {
        Optional<RequestLogEntry> found = requestLogRepository.find(requestId);
        if (found.isPresent()) {
            RequestLogEntry entry = found.get();
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return readBody(entry.responseBody(), type);
        }
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return readBody(entry.responseBody(), type);
        }
    }

    /**
     * 业务成功后回填去重记录响应，与业务变更同事务提交。
     */
    private void complete(String requestId, int status, Object body) {
        requestLogRepository.complete(requestId, status, writeBody(body));
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
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

    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        return sha256(raw.toString());
    }

    /**
     * 操作结果：HTTP 状态码与响应体。
     */
    public record OpOutcome<T>(int status, T body) {
    }
}
