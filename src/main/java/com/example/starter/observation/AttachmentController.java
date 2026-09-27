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

import java.util.List;

/**
 * 观测附件与脱敏 API：附件登记、脱敏、撤销、规则登记/撤销，
 * 以及公开视图、证据视图、历史、诊断与固化引用查询。
 *
 * <p>公开查询只返回最新有效脱敏版本；具备证据权限（role=EVIDENCE）的查询
 * 可按版本读取原始与脱敏摘要、全部历史与固化引用。读取不改变任何状态。
 */
@RestController
@RequestMapping("/api")
@Validated
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    // ---------- 附件登记与公开查询 ----------

    /**
     * 登记附件元数据：附件必须属于存在的未删除观测记录。
     */
    @PostMapping("/observations/{observationId}/attachments")
    public ResponseEntity<AttachmentResponse> register(@PathVariable String observationId,
                                                       @Valid @RequestBody RegisterAttachmentRequest request) {
        AttachmentService.ApiOutcome<AttachmentResponse> outcome =
                attachmentService.register(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 附件公开视图：只返回最新有效脱敏版本的受限展示摘要。
     */
    @GetMapping("/observations/{observationId}/attachments/{attachmentId}")
    public AttachmentPublicView getPublicView(@PathVariable String observationId,
                                              @PathVariable String attachmentId) {
        return attachmentService.getPublicView(observationId, attachmentId);
    }

    /**
     * 列出观测记录下全部附件的公开视图。
     */
    @GetMapping("/observations/{observationId}/attachments")
    public List<AttachmentPublicView> listPublicViews(@PathVariable String observationId) {
        return attachmentService.listPublicViews(observationId);
    }

    // ---------- 脱敏与撤销 ----------

    /**
     * 提交脱敏请求：先校验全部附件的归属、规则版本与角色权限，任一失败整次不产生脱敏版本。
     */
    @PostMapping("/observations/{observationId}/redactions")
    public ResponseEntity<RedactResponse> redact(@PathVariable String observationId,
                                                 @Valid @RequestBody RedactRequest request) {
        AttachmentService.ApiOutcome<RedactResponse> outcome =
                attachmentService.redact(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销脱敏版本：仅撤销公开展示资格，保留脱敏版本、理由和撤销时刻。
     */
    @PostMapping("/observations/{observationId}/attachments/{attachmentId}/redactions/{version}/revoke")
    public ResponseEntity<RedactionVersionView> revoke(@PathVariable String observationId,
                                                       @PathVariable String attachmentId,
                                                       @PathVariable @Min(1) int version,
                                                       @Valid @RequestBody RevokeRedactionRequest request) {
        AttachmentService.ApiOutcome<RedactionVersionView> outcome =
                attachmentService.revoke(observationId, attachmentId, version, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    // ---------- 证据、历史与诊断查询 ----------

    /**
     * 证据视图：按版本读取原始与脱敏摘要；version 为 0 时返回原始摘要。需要证据角色。
     */
    @GetMapping("/observations/{observationId}/attachments/{attachmentId}/versions/{version}")
    public RedactionVersionView getVersionView(@PathVariable String observationId,
                                               @PathVariable String attachmentId,
                                               @PathVariable @Min(0) int version,
                                               @RequestParam(required = false) String role) {
        return attachmentService.getVersionView(observationId, attachmentId, version, role);
    }

    /**
     * 脱敏历史：公开查询只返回有效版本；证据角色返回全部版本。区间端点按左闭右开解释。
     */
    @GetMapping("/observations/{observationId}/attachments/{attachmentId}/history")
    public List<HistoryEntry> getHistory(@PathVariable String observationId,
                                         @PathVariable String attachmentId,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String role) {
        return attachmentService.getHistory(observationId, attachmentId, from, to, role);
    }

    /**
     * 附件诊断：数量类指标只返回实际值。
     */
    @GetMapping("/observations/{observationId}/attachments/{attachmentId}/diagnostics")
    public AttachmentDiagnostics getDiagnostics(@PathVariable String observationId,
                                                @PathVariable String attachmentId) {
        return attachmentService.getDiagnostics(observationId, attachmentId);
    }

    /**
     * 观测版本固化的附件引用：后续脱敏不追溯替换快照中的证据引用。需要证据角色。
     */
    @GetMapping("/observations/{observationId}/versions/{version}/attachment-references")
    public List<FrozenReferenceView> getFrozenReferences(@PathVariable String observationId,
                                                         @PathVariable @Min(1) int version,
                                                         @RequestParam(required = false) String role) {
        return attachmentService.getFrozenReferences(observationId, version, role);
    }

    // ---------- 规则登记与撤销 ----------

    /**
     * 登记脱敏规则版本。
     */
    @PostMapping("/redaction-rules")
    public ResponseEntity<RuleView> registerRule(@Valid @RequestBody RegisterRuleRequest request) {
        AttachmentService.ApiOutcome<RuleView> outcome = attachmentService.registerRule(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销规则版本：撤销后不可再用于新脱敏，既有脱敏版本不受影响。
     */
    @PostMapping("/redaction-rules/{ruleVersion}/revoke")
    public ResponseEntity<RuleView> revokeRule(@PathVariable String ruleVersion,
                                               @Valid @RequestBody RevokeRuleRequest request) {
        AttachmentService.ApiOutcome<RuleView> outcome = attachmentService.revokeRule(ruleVersion, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
