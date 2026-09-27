package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 观测附件脱敏 API：附件登记、规则版本登记、脱敏批次、撤销、物理清除，
 * 以及公开查询、证据查询与诊断查询。角色通过 X-Actor-Role 请求头携带。
 */
@RestController
@Validated
public class RedactionController {

    static final String ROLE_HEADER = "X-Actor-Role";

    private final RedactionService redactionService;

    public RedactionController(RedactionService redactionService) {
        this.redactionService = redactionService;
    }

    /**
     * 现场工程师登记附件元数据。
     */
    @PostMapping("/api/observations/{observationId}/attachments")
    public ResponseEntity<AttachmentMeta> registerAttachment(
            @PathVariable String observationId,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @Valid @RequestBody RegisterAttachmentRequest request) {
        requireRole(roleHeader, ActorRole.FIELD_ENGINEER, ActorRole.OFFICER);
        AttachmentMeta body = redactionService.registerAttachment(observationId, request);
        return ResponseEntity.status(201).body(body);
    }

    /**
     * 脱敏专员登记新的规则版本。
     */
    @PostMapping("/api/redaction-rules")
    public ResponseEntity<RedactionRule> registerRule(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @Valid @RequestBody RegisterRuleRequest request) {
        requireRole(roleHeader, ActorRole.OFFICER);
        RedactionRule body = redactionService.registerRule(
                request.ruleCode(), request.ruleVersion(), request.ruleName());
        return ResponseEntity.status(201).body(body);
    }

    /**
     * 脱敏专员撤销规则版本登记；撤销后不可再用于新脱敏。
     */
    @PostMapping("/api/redaction-rules/{ruleCode}/versions/{ruleVersion}/revoke")
    public ResponseEntity<RedactionRule> revokeRule(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @PathVariable String ruleCode,
            @PathVariable @Min(1) int ruleVersion) {
        requireRole(roleHeader, ActorRole.OFFICER);
        return ResponseEntity.ok(redactionService.revokeRule(ruleCode, ruleVersion));
    }

    /**
     * 脱敏专员提交附件脱敏批次。
     */
    @PostMapping("/api/observations/{observationId}/redactions")
    public ResponseEntity<RedactionBatchResponse> createRedactions(
            @PathVariable String observationId,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @Valid @RequestBody CreateRedactionRequest request) {
        requireRole(roleHeader, ActorRole.OFFICER);
        RedactionService.RedactionBatchOutcome outcome =
                redactionService.createRedactions(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 脱敏专员撤销指定脱敏版本的公开展示资格。
     */
    @PostMapping("/api/redactions/{redactionId}/revoke")
    public ResponseEntity<RedactionVersionResponse> revokeRedaction(
            @PathVariable String redactionId,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @Valid @RequestBody RevokeRedactionRequest request) {
        requireRole(roleHeader, ActorRole.OFFICER);
        RedactionService.RedactionVersionOutcome outcome =
                redactionService.revokeRedaction(redactionId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 脱敏专员物理清除附件原始内容。
     */
    @PostMapping("/api/attachments/{attachmentId}/clear")
    public ResponseEntity<AttachmentClearView> clearAttachment(
            @PathVariable String attachmentId,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @Valid @RequestBody ClearAttachmentContentRequest request) {
        requireRole(roleHeader, ActorRole.OFFICER);
        RedactionService.AttachmentClearOutcome outcome =
                redactionService.clearAttachmentContent(attachmentId, request);
        return ResponseEntity.status(outcome.status())
                .body(new AttachmentClearView(outcome.attachmentId(), true, outcome.clearedAtUtc()));
    }

    /**
     * 公开查询附件最新有效脱敏版本。
     */
    @GetMapping("/api/attachments/{attachmentId}/redactions/public")
    public RedactionVersionResponse getPublicLatest(@PathVariable String attachmentId) {
        return redactionService.getPublicLatest(attachmentId);
    }

    /**
     * 公开查询观测下全部附件的最新有效脱敏版本。
     */
    @GetMapping("/api/observations/{observationId}/redactions/public")
    public List<RedactionVersionResponse> listPublicLatest(@PathVariable String observationId) {
        return redactionService.listPublicLatest(observationId);
    }

    /**
     * 证据查询：附件原始摘要与全部脱敏版本（含已撤销）。
     */
    @GetMapping("/api/attachments/{attachmentId}/evidence")
    public AttachmentEvidenceResponse getEvidence(
            @PathVariable String attachmentId,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader) {
        requireRole(roleHeader, ActorRole.EVIDENCE_READER, ActorRole.OFFICER);
        return redactionService.getEvidence(attachmentId);
    }

    /**
     * 证据查询：按版本标识读取指定脱敏版本（含已撤销）。
     */
    @GetMapping("/api/redactions/{redactionId}/evidence")
    public RedactionVersionResponse getRedactionEvidence(
            @PathVariable String redactionId,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader) {
        requireRole(roleHeader, ActorRole.EVIDENCE_READER, ActorRole.OFFICER);
        return redactionService.getRedactionEvidence(redactionId);
    }

    /**
     * 证据查询：观测下固化的全部附件证据引用。
     */
    @GetMapping("/api/observations/{observationId}/snapshot-refs")
    public List<SnapshotRefResponse> listSnapshotRefs(
            @PathVariable String observationId,
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader) {
        requireRole(roleHeader, ActorRole.EVIDENCE_READER, ActorRole.OFFICER);
        return redactionService.listSnapshotRefs(observationId);
    }

    /**
     * 诊断查询：UTC 左闭右开区间内的脱敏/撤销数量与时长统计。
     */
    @GetMapping("/api/redactions/diagnostics")
    public RedactionDiagnosticsResponse diagnostics(
            @RequestHeader(value = ROLE_HEADER, required = false) String roleHeader,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) Integer requiredCount) {
        requireRole(roleHeader, ActorRole.OFFICER, ActorRole.EVIDENCE_READER);
        return redactionService.diagnostics(from, to, requiredCount);
    }

    private static void requireRole(String roleHeader, ActorRole... allowed) {
        ActorRole role = ActorRole.fromHeader(roleHeader);
        if (role == null) {
            throw ApiException.forbidden("missing or unknown actor role");
        }
        for (ActorRole candidate : allowed) {
            if (role == candidate) {
                return;
            }
        }
        throw ApiException.forbidden("role not allowed: " + role);
    }

    /**
     * 规则版本登记请求。
     */
    public record RegisterRuleRequest(
            @NotBlank @Size(max = 64) String ruleCode,
            @NotNull @Min(1) Integer ruleVersion,
            @NotBlank @Size(max = 256) String ruleName) {
    }

    /**
     * 物理清除响应视图。
     */
    public record AttachmentClearView(String attachmentId, boolean contentCleared, Instant clearedAtUtc) {
    }
}
