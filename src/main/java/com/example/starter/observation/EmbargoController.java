package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测禁运与到期披露 API：批量禁运设置、到期发布、禁运/披露明细与历史、诊断查询。
 */
@RestController
@RequestMapping("/api/observations")
@Validated
public class EmbargoController {

    private final EmbargoService embargoService;

    public EmbargoController(EmbargoService embargoService) {
        this.embargoService = embargoService;
    }

    /**
     * 批量设置禁运：先完整校验全部簇，任一非法整次回滚；全部通过后单事务提交，返回 201。
     */
    @PostMapping("/embargoes")
    public ResponseEntity<EmbargoBatchResponse> setEmbargoes(
            @Valid @RequestBody SetEmbargoBatchRequest request) {
        EmbargoService.EmbargoBatchOutcome outcome = embargoService.setEmbargoes(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询全部禁运设置，按设置时刻、簇标识稳定排序。
     */
    @GetMapping("/embargoes")
    public List<EmbargoItemResponse> listEmbargoes() {
        return embargoService.listEmbargoes();
    }

    /**
     * 查询单簇禁运设置明细；未设置返回 404。
     */
    @GetMapping("/embargoes/{observationId}")
    public EmbargoItemResponse getEmbargo(@PathVariable String observationId) {
        return embargoService.getEmbargo(observationId);
    }

    /**
     * 禁运诊断：汇报簇当前的禁运、发布与未解决冲突状态。
     */
    @GetMapping("/embargoes/{observationId}/status")
    public EmbargoStatusResponse getEmbargoStatus(@PathVariable String observationId) {
        return embargoService.getStatus(observationId);
    }

    /**
     * 到期发布：禁运截止后首次发布生成不可变披露快照（201）；已发布时返回既有快照（200）。
     */
    @PostMapping("/{observationId}/release")
    public ResponseEntity<DisclosureResponse> release(
            @PathVariable String observationId,
            @Valid @RequestBody ReleaseDisclosureRequest request) {
        EmbargoService.ReleaseOutcome outcome = embargoService.release(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询全部披露快照，按发布时刻、簇标识稳定排序。
     */
    @GetMapping("/disclosures")
    public List<DisclosureResponse> listDisclosures() {
        return embargoService.listDisclosures();
    }

    /**
     * 查询单簇披露快照；未发布返回 404。
     */
    @GetMapping("/disclosures/{observationId}")
    public DisclosureResponse getDisclosure(@PathVariable String observationId) {
        return embargoService.getDisclosure(observationId);
    }

    /**
     * 查询簇的披露附页历史，按序号升序稳定排序。
     */
    @GetMapping("/disclosures/{observationId}/appendices")
    public List<AppendixResponse> listAppendices(@PathVariable String observationId) {
        return embargoService.listAppendices(observationId);
    }
}