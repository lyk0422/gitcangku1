package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 观测附件脱敏业务服务。
 *
 * <p>写操作（附件登记/脱敏/撤销/规则登记/规则撤销）均在单事务内完成：先占位写入幂等去重记录，
 * 再对 observation_current 行加锁（SELECT ... FOR UPDATE）串行化同一观测的并发操作，
 * 完成全部校验后一次性提交；任何业务失败都会回滚，去重记录不占键，查询不到半成品。
 *
 * <p>脱敏只追加新的受限展示版本：原始附件摘要、原始观测与既有合并证据不可修改；
 * 撤销仅撤销公开展示资格，保留脱敏版本、理由和撤销时刻。
 * 同一附件同一规则版本只允许生成一个脱敏版本，由唯一键裁决并发。
 *
 * <p>所有时间统一使用 UTC；历史查询区间按左闭右开解释。
 */
@Service
public class AttachmentService {

    /**
     * 允许提交脱敏与撤销请求的角色。
     */
    public static final String ROLE_REDACTOR = "REDACTOR";

    /**
     * 允许读取原始摘要、全部历史与固化引用的证据角色。
     */
    public static final String ROLE_EVIDENCE = "EVIDENCE";

    /**
     * 内容摘要最大长度（字符数），超长返回 422 并携带实际值与要求值。
     */
    public static final int MAX_SUMMARY_LENGTH = 1024;

    private static final String SEPARATOR = "\u0001";

    private final ObservationRepository observationRepository;
    private final AttachmentRepository attachmentRepository;
    private final RedactionRepository redactionRepository;
    private final RedactionRuleRepository ruleRepository;
    private final FrozenReferenceRepository frozenReferenceRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AttachmentService(ObservationRepository observationRepository,
                             AttachmentRepository attachmentRepository,
                             RedactionRepository redactionRepository,
                             RedactionRuleRepository ruleRepository,
                             FrozenReferenceRepository frozenReferenceRepository,
                             RequestLogRepository requestLogRepository,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.observationRepository = observationRepository;
        this.attachmentRepository = attachmentRepository;
        this.redactionRepository = redactionRepository;
        this.ruleRepository = ruleRepository;
        this.frozenReferenceRepository = frozenReferenceRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 附件登记 ----------

    /**
     * 登记附件元数据：附件必须属于存在的未删除观测记录；原始摘要登记后不可修改。
     */
    @Transactional
    public ApiOutcome<AttachmentResponse> register(String observationId, RegisterAttachmentRequest request) {
        checkSummaryLength(request.contentSummary(), "contentSummary");
        String fingerprint = fingerprint("ATTACHMENT", observationId, request.attachmentId(),
                request.contentSummary());
        ApiOutcome<AttachmentResponse> replayed = checkReplay(request.requestId(), fingerprint,
                AttachmentResponse.class);
        if (replayed != null) {
            return replayed;
        }
        ApiOutcome<AttachmentResponse> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "ATTACHMENT", AttachmentResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current = lockObservation(observationId);
        if (current.deleted()) {
            throw ApiException.notFound("observation is deleted: " + observationId);
        }
        Attachment attachment = new Attachment(request.attachmentId(), observationId,
                request.contentSummary(), Instant.now(clock));
        try {
            attachmentRepository.insert(attachment);
        } catch (DuplicateKeyException e) {
            // 附件标识全局唯一：并发或重复登记由主键裁决
            throw ApiException.conflict("attachment already exists: " + request.attachmentId(), null);
        }
        return complete(request.requestId(), HttpStatus.CREATED, AttachmentResponse.of(attachment));
    }

    // ---------- 脱敏 ----------

    /**
     * 提交脱敏请求：先校验全部附件的归属、规则版本与角色权限，再统一写入；
     * 任一失败整次不产生脱敏版本。每个附件生成递增的脱敏版本，仅追加受限展示版本。
     */
    @Transactional
    public ApiOutcome<RedactResponse> redact(String observationId, RedactRequest request) {
        validateItems(request.items());
        for (RedactRequest.RedactItem item : request.items()) {
            checkSummaryLength(item.redactedSummary(), "redactedSummary");
        }
        String fingerprint = fingerprint("REDACT", observationId, request.ruleVersion(),
                request.replacementNote(), request.role(), itemsFingerprint(request.items()));
        ApiOutcome<RedactResponse> replayed = checkReplay(request.requestId(), fingerprint,
                RedactResponse.class);
        if (replayed != null) {
            return replayed;
        }
        ApiOutcome<RedactResponse> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "REDACT", RedactResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        requireRole(request.role(), ROLE_REDACTOR, "redact attachments");
        ObservationSnapshot current = lockObservation(observationId);
        if (current.deleted()) {
            throw ApiException.notFound("observation is deleted: " + observationId);
        }
        RedactionRule rule = ruleRepository.findByVersion(request.ruleVersion())
                .orElseThrow(() -> ApiException.unprocessable(
                        "rule version not registered: " + request.ruleVersion()));
        if (RedactionRule.STATUS_REVOKED.equals(rule.status())) {
            throw ApiException.unprocessable("rule version revoked: " + request.ruleVersion());
        }

        // 先校验全部附件的归属与规则占用，再统一写入，保证整次失败不产生任何脱敏版本。
        List<Attachment> attachments = new ArrayList<>();
        for (RedactRequest.RedactItem item : request.items()) {
            Attachment attachment = attachmentRepository.findById(item.attachmentId())
                    .orElseThrow(() -> ApiException.notFound(
                            "attachment not found in observation: " + item.attachmentId()));
            if (!attachment.observationId().equals(observationId)) {
                throw ApiException.notFound(
                        "attachment not found in observation: " + item.attachmentId());
            }
            if (redactionRepository.existsByRuleVersion(item.attachmentId(), request.ruleVersion())) {
                throw ApiException.conflict("attachment already redacted with rule version: "
                        + request.ruleVersion() + " for attachment: " + item.attachmentId(), null);
            }
            attachments.add(attachment);
        }

        Instant now = Instant.now(clock);
        List<RedactResponse.RedactItemResult> results = new ArrayList<>();
        for (int i = 0; i < attachments.size(); i++) {
            Attachment attachment = attachments.get(i);
            RedactRequest.RedactItem item = request.items().get(i);
            int version = redactionRepository.maxVersion(attachment.attachmentId()) + 1;
            RedactionVersion redaction = new RedactionVersion(
                    attachment.attachmentId(), version, request.ruleVersion(),
                    item.redactedSummary(), request.replacementNote(),
                    RedactionVersion.STATUS_EFFECTIVE, now, null, null, request.requestId());
            try {
                redactionRepository.insert(redaction);
            } catch (DuplicateKeyException e) {
                // 并发下同附件同规则版本：唯一键裁决，后到者整次回滚
                throw ApiException.conflict("attachment already redacted with rule version: "
                        + request.ruleVersion() + " for attachment: " + attachment.attachmentId(), null);
            }
            results.add(new RedactResponse.RedactItemResult(
                    attachment.attachmentId(), version, RedactionVersion.STATUS_EFFECTIVE, now));
        }
        return complete(request.requestId(), HttpStatus.CREATED,
                new RedactResponse(observationId, request.ruleVersion(), results));
    }

    // ---------- 撤销 ----------

    /**
     * 撤销脱敏版本：仅撤销公开展示资格，保留脱敏版本、理由和撤销时刻。
     */
    @Transactional
    public ApiOutcome<RedactionVersionView> revoke(String observationId, String attachmentId,
                                                   int version, RevokeRedactionRequest request) {
        String fingerprint = fingerprint("REVOKE", observationId, attachmentId,
                String.valueOf(version), request.role(), request.reason());
        ApiOutcome<RedactionVersionView> replayed = checkReplay(request.requestId(), fingerprint,
                RedactionVersionView.class);
        if (replayed != null) {
            return replayed;
        }
        ApiOutcome<RedactionVersionView> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "REVOKE", RedactionVersionView.class);
        if (concurrent != null) {
            return concurrent;
        }

        requireRole(request.role(), ROLE_REDACTOR, "revoke redaction");
        ObservationSnapshot current = lockObservation(observationId);
        if (current.deleted()) {
            throw ApiException.notFound("observation is deleted: " + observationId);
        }
       Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound(
                        "attachment not found in observation: " + attachmentId));
        if (!attachment.observationId().equals(observationId)) {
            throw ApiException.notFound("attachment not found in observation: " + attachmentId);
        }
        RedactionVersion version_ = redactionRepository.find(attachmentId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "redaction version not found: " + attachmentId + "@" + version));
        if (RedactionVersion.STATUS_REVOKED.equals(version_.status())) {
            throw ApiException.conflict(
                    "redaction version already revoked: " + attachmentId + "@" + version, null);
        }
        Instant now = Instant.now(clock);
        redactionRepository.markRevoked(attachmentId, version, Timestamp.from(now), request.reason());
        RedactionVersion revoked = new RedactionVersion(
                version_.attachmentId(), version_.redactionVersion(), version_.ruleVersion(),
                version_.redactedSummary(), version_.replacementNote(),
                RedactionVersion.STATUS_REVOKED, version_.createdAtUtc(), now, request.reason(),
                version_.requestId());
        return complete(request.requestId(), HttpStatus.OK, toVersionView(revoked));
    }

    // ---------- 规则登记与撤销 ----------

    /**
     * 登记脱敏规则版本；重复登记返回 409。
     */
    @Transactional
    public ApiOutcome<RuleView> registerRule(RegisterRuleRequest request) {
        String fingerprint = fingerprint("RULE_REGISTER", request.ruleVersion(), request.description());
        ApiOutcome<RuleView> replayed = checkReplay(request.requestId(), fingerprint, RuleView.class);
        if (replayed != null) {
            return replayed;
        }
        ApiOutcome<RuleView> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "RULE_REGISTER", RuleView.class);
        if (concurrent != null) {
            return concurrent;
        }
        RedactionRule rule = new RedactionRule(request.ruleVersion(), request.description(),
                RedactionRule.STATUS_ACTIVE, Instant.now(clock), null);
        try {
            ruleRepository.insert(rule);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("rule version already registered: " + request.ruleVersion(), null);
        }
        return complete(request.requestId(), HttpStatus.CREATED, RuleView.of(rule));
    }

    /**
     * 撤销规则版本：撤销后不可再用于新脱敏，既有脱敏版本不受影响。
     */
    @Transactional
    public ApiOutcome<RuleView> revokeRule(String ruleVersion, RevokeRuleRequest request) {
        String fingerprint = fingerprint("RULE_REVOKE", ruleVersion);
        ApiOutcome<RuleView> replayed = checkReplay(request.requestId(), fingerprint, RuleView.class);
        if (replayed != null) {
            return replayed;
        }
        ApiOutcome<RuleView> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "RULE_REVOKE", RuleView.class);
        if (concurrent != null) {
            return concurrent;
        }
        RedactionRule rule = ruleRepository.findByVersion(ruleVersion)
                .orElseThrow(() -> ApiException.notFound("rule version not registered: " + ruleVersion));
        if (RedactionRule.STATUS_REVOKED.equals(rule.status())) {
            throw ApiException.conflict("rule version already revoked: " + ruleVersion, null);
        }
        Instant now = Instant.now(clock);
        ruleRepository.markRevoked(ruleVersion, Timestamp.from(now));
        return complete(request.requestId(), HttpStatus.OK, RuleView.of(new RedactionRule(
                rule.ruleVersion(), rule.description(), RedactionRule.STATUS_REVOKED,
                rule.registeredAtUtc(), now)));
    }

    // ---------- 查询 ----------

    /**
     * 公开视图：只返回最新有效脱敏版本的受限展示摘要；无有效版本时返回原始摘要。
     */
    @Transactional(readOnly = true)
    public AttachmentPublicView getPublicView(String observationId, String attachmentId) {
        Attachment attachment = findAttachment(observationId, attachmentId);
        Optional<RedactionVersion> latest = redactionRepository
                .findLatestEffective(attachment.attachmentId());
        if (latest.isPresent()) {
            RedactionVersion latestVersion = latest.get();
            return new AttachmentPublicView(attachment.attachmentId(), observationId,
                    latestVersion.redactedSummary(), AttachmentPublicView.SOURCE_REDACTION,
                    latestVersion.redactionVersion());
        }
        return new AttachmentPublicView(attachment.attachmentId(), observationId,
                attachment.contentSummary(), AttachmentPublicView.SOURCE_ORIGINAL, null);
    }

    /**
     * 列出观测记录下全部附件的公开视图，按附件标识排序。
     */
    @Transactional(readOnly = true)
    public List<AttachmentPublicView> listPublicViews(String observationId) {
        observationRepository.findCurrent(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        List<Attachment> attachments = attachmentRepository.findByObservationId(observationId);
        List<AttachmentPublicView> views = new ArrayList<>();
        for (Attachment attachment : attachments) {
            views.add(getPublicView(observationId, attachment.attachmentId()));
        }
        return views;
    }

    /**
     * 证据视图：按版本读取原始与脱敏摘要；version 为 0 时返回原始摘要。
     */
    @Transactional(readOnly = true)
    public RedactionVersionView getVersionView(String observationId, String attachmentId,
                                               int version, String role) {
        requireRole(role, ROLE_EVIDENCE, "read redaction evidence");
        Attachment attachment = findAttachment(observationId, attachmentId);
        if (version == 0) {
            return new RedactionVersionView(attachment.attachmentId(), 0, null,
                    attachment.contentSummary(), null, RedactionVersionView.STATUS_ORIGINAL,
                    attachment.createdAtUtc(), null, null);
        }
        RedactionVersion version_ = redactionRepository.find(attachmentId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "redaction version not found: " + attachmentId + "@" + version));
        return toVersionView(version_);
    }

    /**
     * 脱敏历史：证据角色返回全部版本全部字段；公开查询只返回有效版本且不含替换说明与撤销信息。
     * 区间端点按左闭右开解释。
     */
    @Transactional(readOnly = true)
    public List<HistoryEntry> getHistory(String observationId, String attachmentId,
                                         String from, String to, String role) {
        Attachment attachment = findAttachment(observationId, attachmentId);
        Instant fromInstant = parseBoundary(from, "from");
        Instant toInstant = parseBoundary(to, "to");
        if (fromInstant != null && toInstant != null && !fromInstant.isBefore(toInstant)) {
            throw ApiException.badRequest("from must be before to (left-closed right-open interval); from="
                    + fromInstant + ", to=" + toInstant);
        }
       boolean evidence = ROLE_EVIDENCE.equals(role);
        List<HistoryEntry> entries = new ArrayList<>();
        for (RedactionVersion version : redactionRepository.findByAttachment(attachment.attachmentId())) {
            if (fromInstant != null && version.createdAtUtc().isBefore(fromInstant)) {
                continue;
            }
            if (toInstant != null && !version.createdAtUtc().isBefore(toInstant)) {
                continue;
            }
            if (!evidence && !RedactionVersion.STATUS_EFFECTIVE.equals(version.status())) {
                continue;
            }
            entries.add(toHistoryEntry(version, evidence));
        }
        return entries;
    }

    /**
     * 附件诊断：数量类指标只返回实际值。
     */
    @Transactional(readOnly = true)
    public AttachmentDiagnostics getDiagnostics(String observationId, String attachmentId) {
        Attachment attachment = findAttachment(observationId, attachmentId);
        List<RedactionVersion> versions = redactionRepository.findByAttachment(attachment.attachmentId());
        int total = versions.size();
        int effective = 0;
        int revoked = 0;
        int latestVersion = 0;
        Integer latestEffectiveVersion = null;
        for (RedactionVersion version : versions) {
            latestVersion = Math.max(latestVersion, version.redactionVersion());
            if (RedactionVersion.STATUS_EFFECTIVE.equals(version.status())) {
                effective++;
                latestEffectiveVersion = version.redactionVersion();
            } else if (RedactionVersion.STATUS_REVOKED.equals(version.status())) {
                revoked++;
            }
        }
        return new AttachmentDiagnostics(attachment.attachmentId(), observationId,
                total, effective, revoked, latestVersion, latestEffectiveVersion);
    }

    /**
     * 查询观测版本固化的附件引用；需要证据角色。
     */
    @Transactional(readOnly = true)
    public List<FrozenReferenceView> getFrozenReferences(String observationId, int version, String role) {
        requireRole(role, ROLE_EVIDENCE, "read frozen references");
        observationRepository.findVersion(observationId, version)
                .orElseThrow(() -> ApiException.notFound(
                        "version not found: " + observationId + "@" + version));
        return frozenReferenceRepository.findByObservationVersion(observationId, version).stream()
                .map(FrozenReferenceView::of)
                .toList();
    }

    /**
     * 生成观测版本时固化附件引用：记录当时各附件引用的最新有效脱敏版本（无则引用原始摘要）。
     * 由合并与人工冲突解决在同事务内调用，后续脱敏不追溯改写。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void freezeReferencesForVersion(String observationId, int version) {
        List<Attachment> attachments = attachmentRepository.findByObservationId(observationId);
        if (attachments.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        for (Attachment attachment : attachments) {
            Integer redactionVersion = redactionRepository
                    .findLatestEffective(attachment.attachmentId())
                    .map(RedactionVersion::redactionVersion)
                    .orElse(null);
            frozenReferenceRepository.insert(new FrozenReference(
                    observationId, version, attachment.attachmentId(), redactionVersion, now));
        }
    }

    // ---------- 内部辅助 ----------

    private ObservationSnapshot lockObservation(String observationId) {
        return observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
    }

    private Attachment findAttachment(String observationId, String attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound(
                        "attachment not found in observation: " + attachmentId));
        if (!attachment.observationId().equals(observationId)) {
            throw ApiException.notFound("attachment not found in observation: " + attachmentId);
        }
        return attachment;
    }

    private void requireRole(String role, String required, String action) {
        if (!required.equals(role)) {
            throw ApiException.forbidden("role " + required + " required to " + action);
        }
    }

    private void checkSummaryLength(String summary, String fieldName) {
        int actual = summary.length();
        if (actual > MAX_SUMMARY_LENGTH) {
            throw ApiException.unprocessable(
                    fieldName + " length " + actual + " exceeds maximum " + MAX_SUMMARY_LENGTH,
                    actual, MAX_SUMMARY_LENGTH);
        }
    }

    private void validateItems(List<RedactRequest.RedactItem> items) {
        Set<String> seen = new HashSet<>();
        for (RedactRequest.RedactItem item : items) {
            if (!seen.add(item.attachmentId())) {
                throw ApiException.badRequest("duplicate attachment in request: " + item.attachmentId());
            }
        }
    }

    private String itemsFingerprint(List<RedactRequest.RedactItem> items) {
        StringBuilder raw = new StringBuilder();
        for (RedactRequest.RedactItem item : items) {
            raw.append(SEPARATOR).append(item.attachmentId())
                    .append(SEPARATOR).append(item.redactedSummary());
        }
        return raw.toString();
    }

    private Instant parseBoundary(String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(name + " must be an ISO-8601 UTC instant: " + value);
        }
    }

    private HistoryEntry toHistoryEntry(RedactionVersion version, boolean evidence) {
        if (evidence) {
            return new HistoryEntry(version.attachmentId(), version.redactionVersion(),
                    version.ruleVersion(), version.redactedSummary(), version.replacementNote(),
                    version.status(), version.createdAtUtc(), version.revokedAtUtc(),
                    version.revokeReason());
        }
        return new HistoryEntry(version.attachmentId(), version.redactionVersion(),
                version.ruleVersion(), version.redactedSummary(), null,
                version.status(), version.createdAtUtc(), null, null);
    }

    private RedactionVersionView toVersionView(RedactionVersion version) {
        return new RedactionVersionView(version.attachmentId(), version.redactionVersion(),
                version.ruleVersion(), version.redactedSummary(), version.replacementNote(),
                version.status(), version.createdAtUtc(), version.revokedAtUtc(),
                version.revokeReason());
    }

    // ---------- 幂等去重（与 ObservationService 同一套占位语义） ----------

    private <T> ApiOutcome<T> checkReplay(String requestId, String fingerprint, Class<T> type) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new ApiOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), type));
    }

    private <T> ApiOutcome<T> insertPlaceholder(String requestId, String fingerprint, String operation,
                                                Class<T> type) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new ApiOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), type));
        }
    }

    private <T> ApiOutcome<T> complete(String requestId, HttpStatus status, T body) {
        requestLogRepository.complete(requestId, status.value(), writeBody(body));
        return new ApiOutcome<>(status.value(), body);
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

    private <T> T readBody(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record ApiOutcome<T>(int status, T body) {
    }
}
