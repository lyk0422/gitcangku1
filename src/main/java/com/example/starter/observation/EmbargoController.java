package com.example.starter.observation;

import jakarta.validation.Valid;
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
 * 观测禁运与到期披露 API：批量禁运设置、到期公开发布、公开查询、授权证据查询、
 * 披露明细、附页历史与禁运诊断。所有时间均为 UTC，禁运区间左闭右开。
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
     * 批量设置禁运：全量校验通过后单事务提交；任一簇非法整次 422 回滚。
     */
    @PostMapping("/embargo")
    public ResponseEntity<EmbargoResponse> setEmbargo(@Valid @RequestBody EmbargoSetRequest request) {
        EmbargoService.EmbargoOutcome outcome = embargoService.setEmbargo(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 到期公开发布：到期后首次发布生成不可变披露快照；未到期 422；重复发布返回首次快照。
     */
    @PostMapping("/{observationId}/release")
    public ResponseEntity<DisclosureResponse> release(@PathVariable String observationId,
                                                      @Valid @RequestBody ReleaseRequest request) {
        EmbargoService.ReleaseOutcome outcome = embargoService.release(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 公开查询：禁运期内不返回簇内容；已发布返回首次披露快照；否则返回当前内容。
     */
    @GetMapping("/{observationId}/public")
    public PublicObservationResponse publicView(@PathVariable String observationId) {
        return embargoService.publicView(observationId);
    }

    /**
     * 授权证据查询：需携带非空 operator 参数，不受禁运限制返回当前内容与禁运状态。
     */
    @GetMapping("/{observationId}/evidence")
    public EvidenceResponse evidence(@PathVariable String observationId,
                                     @RequestParam(required = false) String operator) {
        return embargoService.evidence(observationId, operator);
    }

    /**
     * 披露明细查询：返回首次披露快照及全部附页；未发布返回 404。
     */
    @GetMapping("/{observationId}/disclosure")
    public DisclosureResponse disclosure(@PathVariable String observationId) {
        return embargoService.disclosure(observationId);
    }

    /**
     * 附页历史查询：按 seq 升序返回，排序稳定。
     */
    @GetMapping("/{observationId}/disclosure/appendices")
    public List<AppendixRecord> appendices(@PathVariable String observationId) {
        return embargoService.appendices(observationId);
    }

    /**
     * 禁运诊断查询：禁运设置、到期状态（含剩余毫秒实际值）、未解决冲突数与发布状态。
     */
    @GetMapping("/{observationId}/embargo")
    public EmbargoDiagnosticsResponse diagnostics(@PathVariable String observationId) {
        return embargoService.diagnostics(observationId);
    }
}