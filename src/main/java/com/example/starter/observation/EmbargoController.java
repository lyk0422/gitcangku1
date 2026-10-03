package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 观测禁运与到期发布 API：批量禁运设置、到期发布、发布后更正、
 * 公开查询、授权证据查询、禁运明细、披露历史与诊断查询。
 */
@RestController
@RequestMapping("/api/observations")
@Validated
public class EmbargoController {

    private final EmbargoService embargoService;
    private final String evidenceKey;

    public EmbargoController(EmbargoService embargoService,
                             @Value("${observation.evidence-key:local-evidence-key}") String evidenceKey) {
        this.embargoService = embargoService;
        this.evidenceKey = evidenceKey;
    }

    /**
     * 批量设置禁运：任一簇非法整次回滚；同参重放返回首次响应，异参 409。
     */
    @PostMapping("/embargoes")
    public ResponseEntity<SetEmbargoResponse> setEmbargoes(@Valid @RequestBody SetEmbargoRequest request) {
        EmbargoService.SetEmbargoOutcome outcome = embargoService.setEmbargoes(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 到期发布：禁运到期后首次发布生成不可变披露快照；同簇重复发布返回既有快照。
     */
    @PostMapping("/{observationId}/release")
    public ResponseEntity<DisclosureResponse> release(@PathVariable String observationId,
                                                      @Valid @RequestBody ReleaseRequest request) {
        EmbargoService.ReleaseOutcome outcome = embargoService.release(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 发布后更正：只追加附页，不改写首次快照。
     */
    @PostMapping("/{observationId}/corrections")
    public ResponseEntity<AppendixResponse> correct(@PathVariable String observationId,
                                                    @Valid @RequestBody CorrectionRequest request) {
        EmbargoService.CorrectOutcome outcome = embargoService.correct(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 公开查询：禁运截止前不返回簇内容（403）；已发布返回首次快照与全部附页。
     */
    @GetMapping("/{observationId}/public")
    public PublicObservationResponse getPublic(@PathVariable String observationId) {
        return embargoService.getPublic(observationId);
    }

    /**
     * 授权证据查询：需携带匹配的 X-Evidence-Key 头；不受禁运限制。
     */
    @GetMapping("/{observationId}/evidence")
    public EvidenceResponse getEvidence(@PathVariable String observationId,
                                        @RequestHeader(value = "X-Evidence-Key", required = false) String key) {
        if (key == null || !key.equals(evidenceKey)) {
            throw ApiException.forbidden(EmbargoService.REASON_EVIDENCE_UNAUTHORIZED,
                    "evidence access denied for observation: " + observationId);
        }
        return embargoService.getEvidence(observationId);
    }

    /**
     * 禁运明细查询。
     */
    @GetMapping("/{observationId}/embargo")
    public EmbargoResponse getEmbargo(@PathVariable String observationId) {
        return embargoService.getEmbargo(observationId);
    }

    /**
     * 披露历史查询：首次快照与全部更正附页（按 seq 升序）。
     */
    @GetMapping("/{observationId}/disclosure")
    public DisclosureResponse getDisclosure(@PathVariable String observationId) {
        return embargoService.getDisclosure(observationId);
    }

    /**
     * 禁运诊断查询：返回判定所需的实际值与差额。
     */
    @GetMapping("/{observationId}/embargo-diagnostics")
    public EmbargoDiagnosticsResponse getDiagnostics(@PathVariable String observationId) {
        return embargoService.getDiagnostics(observationId);
    }
}