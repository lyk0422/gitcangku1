package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 观测附件脱敏 API：附件/规则登记、脱敏提交与撤销、物理清除，
 * 以及公开受限查询、证据明细/历史、固化引用与诊断查询。
 */
@RestController
@RequestMapping("/api")
@Validated
public class RedactionController {

    private final RedactionService redactionService;

    public RedactionController(RedactionService redactionService) {
        this.redactionService = redactionService;
    }

    // ---------- 附件登记 ----------

    /**
     * 登记观测附件元数据（归属与原始摘要不可修改）。
     */
    @PostMapping("/observations/{observationId}/attachments")
    public ResponseEntity<AttachmentResponse> registerAttachment(@PathVariable String observationId,
                                                                 @Valid @RequestBody RegisterAttachmentRequest request) {
        RedactionService.OpOutcome<AttachmentResponse> outcome =
                redactionService.registerAttachment(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    // ---------- 规则 ----------

    /**
     * 登记脱敏规则版本（ACTIVE）。
     */
    @PostMapping("/redaction-rules")
    public ResponseEntity<RuleResponse> registerRule(@Valid @RequestBody RegisterRuleRequest request) {
        RedactionService.OpOutcome<RuleResponse> outcome = redactionService.registerRule(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销脱敏规则版本（不可逆）。
     */
    @PostMapping("/redaction-rules/{ruleVersion}/revoke")
    public ResponseEntity<RuleResponse> revokeRule(@PathVariable String ruleVersion,
                                                   @Valid @RequestBody RevokeRuleRequest request) {
        RedactionService.OpOutcome<RuleResponse> outcome = redactionService.revokeRule(ruleVersion, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 读取规则版本明细（证据权限）。
     */
    @GetMapping("/redaction-rules/{ruleVersion}")
    public RuleResponse getRule(@PathVariable String ruleVersion,
                                @RequestParam(name = "role", required = false) String role) {
        return redactionService.getRule(ruleVersion, role);
    }

    // ---------- 脱敏提交与撤销 ----------

    /**
     * 批量提交附件脱敏：整次校验、整次提交。
     */
    @PostMapping("/observations/{observationId}/redactions")
    public ResponseEntity<RedactOutcomeResponse> redact(@PathVariable String observationId,
                                                        @Valid @RequestBody RedactAttachmentsRequest request) {
        RedactionService.OpOutcome<RedactOutcomeResponse> outcome =
                redactionService.redact(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销指定脱敏版本的公开展示资格（保留版本、理由与撤销时刻）。
     */
    @PostMapping("/attachments/{attachmentId}/redactions/{version}/revoke")
    public ResponseEntity<RedactionVersionResponse> revokeRedaction(@PathVariable String attachmentId,
                                                                    @PathVariable @Min(1) int version,
                                                                    @Valid @RequestBody RevokeRedactionRequest request) {
        RedactionService.OpOutcome<RedactionVersionResponse> outcome =
                redactionService.revokeRedaction(attachmentId, version, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 物理清除附件内容（ADMIN），原始摘要不可恢复。
     */
    @PostMapping("/attachments/{attachmentId}/purge")
    public ResponseEntity<AttachmentResponse> purgeAttachment(@PathVariable String attachmentId,
                                                              @Valid @RequestBody PurgeAttachmentRequest request) {
        RedactionService.OpOutcome<AttachmentResponse> outcome =
                redactionService.purgeAttachment(attachmentId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    // ---------- 查询 ----------

    /**
     * 公开查询：只返回该观测各附件最新有效脱敏版本的受限展示内容。
     */
    @GetMapping("/observations/{observationId}/attachments/public")
    public List<PublicAttachmentResponse> getPublicAttachments(@PathVariable String observationId) {
        return redactionService.getPublicAttachments(observationId);
    }

    /**
     * 证据权限：读取附件原始明细（含原始摘要或清除状态）。
     */
    @GetMapping("/attachments/{attachmentId}/evidence")
    public AttachmentResponse getAttachmentEvidence(@PathVariable String attachmentId,
                                                    @RequestParam(name = "role", required = false) String role) {
        return redactionService.getAttachmentEvidence(attachmentId, role);
    }

    /**
     * 证据权限：读取附件全部脱敏版本历史（含已撤销，按版本升序）。
     */
    @GetMapping("/attachments/{attachmentId}/redactions")
    public List<RedactionVersionResponse> getAttachmentHistory(@PathVariable String attachmentId,
                                                               @RequestParam(name = "role", required = false) String role) {
        return redactionService.getAttachmentHistory(attachmentId, role);
    }

    /**
     * 证据权限：按版本读取脱敏摘要明细。
     */
    @GetMapping("/attachments/{attachmentId}/redactions/{version}")
    public RedactionVersionResponse getRedactionVersion(@PathVariable String attachmentId,
                                                        @PathVariable @Min(1) int version,
                                                        @RequestParam(name = "role", required = false) String role) {
        return redactionService.getRedactionVersion(attachmentId, version, role);
    }

    /**
     * 证据权限：读取观测版本固化的附件证据引用（后续脱敏不追溯替换）。
     */
    @GetMapping("/observations/{observationId}/versions/{version}/attachments")
    public List<VersionAttachmentRefResponse> getVersionAttachments(@PathVariable String observationId,
                                                                    @PathVariable @Min(1) int version,
                                                                    @RequestParam(name = "role", required = false) String role) {
        return redactionService.getVersionAttachmentRefs(observationId, version, role);
    }

    /**
     * 证据权限：脱敏诊断查询。时间区间左闭右开 [from, to)；只读不改变状态。
     */
    @GetMapping("/observations/{observationId}/redactions/diagnostics")
    public RedactionDiagnosticsResponse getDiagnostics(
            @PathVariable String observationId,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "expectedCount", required = false) Integer expectedCount,
            @RequestParam(name = "role", required = false) String role) {
        return redactionService.getDiagnostics(observationId, from, to, expectedCount, role);
    }
}
