package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 观测附件脱敏 API：附件/规则登记、脱敏提交与撤销、物理清除、公开附件视图、证据按版本读取与 UTC 区间诊断。
 * 角色由请求头 X-Role 携带：缺省 PUBLIC；REDACTION_OFFICER 可写，EVIDENCE_READER 可读证据。
 */
@RestController
@RequestMapping("/api")
@Validated
public class RedactionController {

    static final String ROLE_HEADER = "X-Role";

    private final RedactionService redactionService;

    public RedactionController(RedactionService redactionService) {
        this.redactionService = redactionService;
    }

    // ---------- 规则版本管理 ----------

    /**
     * 登记脱敏规则版本（脱敏官）。
     */
    @PostMapping("/redaction/rules")
    public ResponseEntity<RedactionRuleResponse> registerRule(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @Valid @RequestBody RegisterRuleRequest request) {
        RedactionService.WriteOutcome<RedactionRuleResponse> outcome =
                redactionService.registerRule(request, resolveRole(roleHeader));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销脱敏规则版本（脱敏官）：撤销后不可用于新脱敏请求，既有脱敏版本不受影响。
     */
    @PostMapping("/redaction/rules/{ruleVersion}/revoke")
    public ResponseEntity<RedactionRuleResponse> revokeRule(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @PathVariable String ruleVersion,
            @Valid @RequestBody RevokeRuleRequest request) {
        RedactionService.WriteOutcome<RedactionRuleResponse> outcome =
                redactionService.revokeRule(ruleVersion, request, resolveRole(roleHeader));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    // ---------- 附件登记与物理清除 ----------

    /**
     * 登记观测附件（脱敏官）。
     */
    @PostMapping("/observations/{observationId}/attachments")
    public ResponseEntity<AttachmentResponse> registerAttachment(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @PathVariable String observationId,
            @Valid @RequestBody RegisterAttachmentRequest request) {
        RedactionService.WriteOutcome<AttachmentResponse> outcome =
                redactionService.registerAttachment(observationId, request, resolveRole(roleHeader));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 物理清除附件原始内容（脱敏官）：清除不可恢复，既有脱敏版本与证据引用保留。
     */
    @PostMapping("/observations/{observationId}/attachments/{attachmentId}/purge")
    public ResponseEntity<PurgeAttachmentResponse> purgeAttachment(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @PathVariable String observationId,
            @PathVariable String attachmentId,
            @Valid @RequestBody PurgeAttachmentRequest request) {
        RedactionService.WriteOutcome<PurgeAttachmentResponse> outcome =
                redactionService.purgeAttachment(observationId, attachmentId, request, resolveRole(roleHeader));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    // ---------- 脱敏与撤销 ----------

    /**
     * 提交附件脱敏请求（脱敏官）：多附件先全量校验，任一失败整次不产生脱敏版本。
     */
    @PostMapping("/observations/{observationId}/redactions")
    public ResponseEntity<RedactionResponse> redact(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @PathVariable String observationId,
            @Valid @RequestBody RedactAttachmentsRequest request) {
        RedactionService.WriteOutcome<RedactionResponse> outcome =
                redactionService.redact(observationId, request, resolveRole(roleHeader));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销附件脱敏版本的公开展示资格（脱敏官）：版本、理由与撤销时刻保留。
     */
    @PostMapping("/observations/{observationId}/attachments/{attachmentId}/redactions/{redactionVersion}/revoke")
    public ResponseEntity<RedactionRevokeResponse> revokeRedaction(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @PathVariable String observationId,
            @PathVariable String attachmentId,
            @PathVariable @Min(1) int redactionVersion,
            @Valid @RequestBody RevokeRedactionRequest request) {
        RedactionService.WriteOutcome<RedactionRevokeResponse> outcome = redactionService.revokeRedaction(
                observationId, attachmentId, redactionVersion, request, resolveRole(roleHeader));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    // ---------- 查询 ----------

    /**
     * 公开附件视图：只返回最新有效脱敏版本的受限展示摘要；无有效脱敏时返回原始摘要。
     */
    @GetMapping("/observations/{observationId}/attachments")
    public List<PublicAttachmentView> listPublicAttachments(@PathVariable String observationId) {
        return redactionService.listPublicAttachments(observationId);
    }

    /**
     * 附件证据视图（证据读取者）：按版本返回原始摘要与全部脱敏版本（含已撤销）。
     */
    @GetMapping("/observations/{observationId}/attachments/{attachmentId}/evidence")
    public AttachmentEvidenceView getAttachmentEvidence(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @PathVariable String observationId,
            @PathVariable String attachmentId) {
        return redactionService.getAttachmentEvidence(observationId, attachmentId, resolveRole(roleHeader));
    }

    /**
     * 观测版本证据视图（证据读取者）：返回该版本生成时固化、后续不回写的附件证据引用。
     */
    @GetMapping("/observations/{observationId}/versions/{version}/evidence")
    public VersionEvidenceView getVersionEvidence(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @PathVariable String observationId,
            @PathVariable @Min(1) int version) {
        return redactionService.getVersionEvidence(observationId, version, resolveRole(roleHeader));
    }

    /**
     * 脱敏诊断（证据读取者）：统计 UTC 区间 [fromUtc, toUtc)（左闭右开）内生成的脱敏版本数量，
     * 返回实际值、要求值与差额。
     */
    @GetMapping("/redaction/diagnostics")
    public RedactionDiagnosticsResponse diagnostics(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @RequestParam String fromUtc,
            @RequestParam String toUtc,
            @RequestParam(defaultValue = "0") long expectedCount,
            @RequestParam(required = false) Long expectedDurationSeconds) {
        Instant from = parseInstant("fromUtc", fromUtc);
        Instant to = parseInstant("toUtc", toUtc);
        return redactionService.diagnostics(from, to, expectedCount, expectedDurationSeconds,
                resolveRole(roleHeader));
    }

    /**
     * 解析 ISO-8601 UTC 时刻查询参数；非法格式返回 400。
     */
    private Instant parseInstant(String name, String raw) {
        try {
            return Instant.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("invalid UTC instant for " + name + ": " + raw);
        }
    }

    /**
     * 解析请求角色：缺省为 PUBLIC；无法识别的角色按未授权 403 处理。
     */
    private RequesterRole resolveRole(String roleHeader) {
        RequesterRole role = RequesterRole.fromHeader(roleHeader);
        if (role == null) {
            throw ApiException.forbidden("unknown role: " + roleHeader);
        }
        return role;
    }
}
