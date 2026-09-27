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
 * 观测附件脱敏 API：附件登记、规则登记/撤销、批量脱敏、撤销、物理清除，
 * 以及公开视图、证据版本、明细、历史、诊断与固化证据查询。
 *
 * <p>角色经请求头 X-Actor-Role 传入（PUBLIC/REDACTOR/EVIDENCE/ADMIN，缺省 PUBLIC）；
 * 无法识别的角色值按 403 拒绝。所有时间参数与响应均为 UTC（ISO-8601），历史区间按左闭右开解释。
 */
@RestController
@RequestMapping("/api")
@Validated
public class RedactionController {

    private final RedactionService redactionService;

    public RedactionController(RedactionService redactionService) {
        this.redactionService = redactionService;
    }

    /**
     * 登记附件元数据（REDACTOR/EVIDENCE）。
     */
    @PostMapping("/observations/{observationId}/attachments")
    public ResponseEntity<AttachmentResponse> registerAttachment(
            @PathVariable String observationId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            @Valid @RequestBody RegisterAttachmentRequest request) {
        RedactionService.RedactionOutcome<AttachmentResponse> outcome =
                redactionService.registerAttachment(observationId, parseRole(roleHeader), request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 登记脱敏规则版本（ADMIN）。
     */
    @PostMapping("/redaction-rules")
    public ResponseEntity<RedactionRuleResponse> registerRule(
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            @Valid @RequestBody RegisterRuleRequest request) {
        RedactionService.RedactionOutcome<RedactionRuleResponse> outcome =
                redactionService.registerRule(parseRole(roleHeader), request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销脱敏规则版本（ADMIN）。
     */
    @PostMapping("/redaction-rules/{ruleVersion}/revoke")
    public ResponseEntity<RevokeResultResponse> revokeRule(
            @PathVariable String ruleVersion,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            @Valid @RequestBody RevokeRuleRequest request) {
        RedactionService.RedactionOutcome<RevokeResultResponse> outcome =
                redactionService.revokeRule(ruleVersion, parseRole(roleHeader), request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询规则版本登记状态（ADMIN/REDACTOR/EVIDENCE）。
     */
    @GetMapping("/redaction-rules/{ruleVersion}")
    public RedactionRuleResponse getRule(
            @PathVariable String ruleVersion,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        return redactionService.getRule(ruleVersion, parseRole(roleHeader));
    }

    /**
     * 批量脱敏提交（REDACTOR）：先校验全部归属、规则版本与权限，任一失败整次不产生脱敏版本。
     */
    @PostMapping("/observations/{observationId}/redactions")
    public ResponseEntity<RedactionBatchResponse> createRedactions(
            @PathVariable String observationId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            @Valid @RequestBody CreateRedactionRequest request) {
        RedactionService.RedactionOutcome<RedactionBatchResponse> outcome =
                redactionService.createRedactions(observationId, parseRole(roleHeader), request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销脱敏版本公开展示资格（REDACTOR/ADMIN）。
     */
    @PostMapping("/redactions/{redactionId}/revoke")
    public ResponseEntity<RevokeResultResponse> revokeRedaction(
            @PathVariable String redactionId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            @Valid @RequestBody RevokeRedactionRequest request) {
        RedactionService.RedactionOutcome<RevokeResultResponse> outcome =
                redactionService.revokeRedaction(redactionId, parseRole(roleHeader), request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 物理清除附件原始内容（ADMIN）：不可逆，撤销脱敏不能恢复已清除内容。
     */
    @PostMapping("/observations/{observationId}/attachments/{attachmentId}/purge")
    public ResponseEntity<AttachmentResponse> purgeAttachment(
            @PathVariable String observationId,
            @PathVariable String attachmentId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            @Valid @RequestBody PurgeAttachmentRequest request) {
        RedactionService.RedactionOutcome<AttachmentResponse> outcome =
                redactionService.purgeAttachment(observationId, attachmentId, parseRole(roleHeader), request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 公开视图：列出观测下附件的最新有效脱敏展示（无有效版本回退原始摘要）。
     */
    @GetMapping("/observations/{observationId}/attachments")
    public List<AttachmentViewResponse> listAttachmentViews(
            @PathVariable String observationId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        return redactionService.listAttachmentViews(observationId, parseRole(roleHeader));
    }

    /**
     * 单个附件公开视图。
     */
    @GetMapping("/observations/{observationId}/attachments/{attachmentId}")
    public AttachmentViewResponse getAttachmentView(
            @PathVariable String observationId,
            @PathVariable String attachmentId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        return redactionService.getAttachmentView(observationId, attachmentId, parseRole(roleHeader));
    }

    /**
     * 证据权限按版本读取：version=0 原始摘要，version≥1 指定脱敏版本（含已撤销）。
     */
    @GetMapping("/observations/{observationId}/attachments/{attachmentId}/versions/{version}")
    public AttachmentViewResponse getEvidenceVersion(
            @PathVariable String observationId,
            @PathVariable String attachmentId,
            @PathVariable @Min(0) int version,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        return redactionService.getEvidenceVersion(observationId, attachmentId, version, parseRole(roleHeader));
    }

    /**
     * 明细查询：观测下全部脱敏版本（REDACTOR/EVIDENCE）。
     */
    @GetMapping("/observations/{observationId}/redactions")
    public List<RedactionVersionResponse> listRedactions(
            @PathVariable String observationId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        return redactionService.listRedactions(observationId, parseRole(roleHeader));
    }

    /**
     * 历史查询：UTC 左闭右开区间 [from, to) 过滤生成时刻（REDACTOR/EVIDENCE）。
     */
    @GetMapping("/observations/{observationId}/redaction-history")
    public List<RedactionVersionResponse> findRedactionHistory(
            @PathVariable String observationId,
            @RequestParam(required = false) String attachmentId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        return redactionService.findRedactionHistory(observationId, attachmentId, from, to,
                parseRole(roleHeader));
    }

    /**
     * 诊断查询：附件与脱敏版本的实际数量及逐附件差额（REDACTOR/EVIDENCE）。
     */
    @GetMapping("/observations/{observationId}/redaction-diagnostics")
    public RedactionDiagnosticsResponse getDiagnostics(
            @PathVariable String observationId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        return redactionService.getDiagnostics(observationId, parseRole(roleHeader));
    }

    /**
     * 查询观测版本固化的合并/人工选择证据（EVIDENCE）。
     */
    @GetMapping("/observations/{observationId}/versions/{version}/evidence")
    public List<EvidenceResponse> getEvidence(
            @PathVariable String observationId,
            @PathVariable @Min(1) int version,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        return redactionService.getEvidence(observationId, version, parseRole(roleHeader));
    }

    private ActorRole parseRole(String roleHeader) {
        ActorRole role = ActorRole.fromHeader(roleHeader);
        if (role == null) {
            throw ApiException.forbidden("unrecognized actor role: " + roleHeader, "FORBIDDEN_ROLE");
        }
        return role;
    }
}
