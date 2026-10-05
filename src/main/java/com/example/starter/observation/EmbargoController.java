package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
 * 观测禁运与到期发布 API：批量设置禁运、到期发布、披露快照/附页明细、诊断与授权证据查询。
 */
@RestController
@RequestMapping("/api/observations")
@Validated
public class EmbargoController {

    private final EmbargoService embargoService;
    private final ObservationService observationService;

    public EmbargoController(EmbargoService embargoService, ObservationService observationService) {
        this.embargoService = embargoService;
        this.observationService = observationService;
    }

    /**
     * 批量设置禁运：全部条目校验通过后单事务原子生效；任一条目非法整次回滚。
     */
    @PostMapping("/embargo")
    public ResponseEntity<EmbargoBatchResponse> setEmbargoBatch(@Valid @RequestBody SetEmbargoRequest request) {
        EmbargoService.EmbargoOutcome outcome = embargoService.setEmbargoBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 到期发布：禁运到期后生成不可变披露快照；未到期返回 422。
     */
    @PostMapping("/{observationId}/embargo/release")
    public ResponseEntity<DisclosureResponse> release(@PathVariable String observationId,
                                                      @Valid @RequestBody ReleaseEmbargoRequest request) {
        EmbargoService.ReleaseOutcome outcome = embargoService.release(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 授权证据查询：禁运期内仅设置禁运的操作者可查看簇内容。
     */
    @GetMapping("/{observationId}/evidence")
    public ObservationResponse getEvidence(@PathVariable String observationId,
                                           @RequestParam @Size(max = 128) String operator) {
        return observationService.getEvidence(observationId, operator);
    }

    /**
     * 查询首次披露快照（明细查询）；未发布返回 404。
     */
    @GetMapping("/{observationId}/disclosure")
    public DisclosureResponse getDisclosure(@PathVariable String observationId) {
        return embargoService.getDisclosure(observationId);
    }

    /**
     * 查询更正附页历史，按附页序号升序。
     */
    @GetMapping("/{observationId}/disclosure/addenda")
    public List<AddendumResponse> listAddenda(@PathVariable String observationId) {
        return embargoService.listAddenda(observationId);
    }

    /**
     * 诊断查询：禁运状态、发布状态与未解决冲突明细。
     */
    @GetMapping("/{observationId}/diagnostics")
    public DiagnosticsResponse getDiagnostics(@PathVariable String observationId) {
        return embargoService.getDiagnostics(observationId);
    }
}
