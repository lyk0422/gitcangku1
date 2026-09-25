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
 * 观测更正附页 API：单条/批量提交、撤销、附页链、撤销记录、待复审标记与导出视图查询。
 */
@RestController
@RequestMapping("/api/observations")
@Validated
public class CorrigendumController {

    private final CorrigendumService corrigendumService;

    public CorrigendumController(CorrigendumService corrigendumService) {
        this.corrigendumService = corrigendumService;
    }

    /**
     * 提交单条更正附页：指定原观测版本、字段差异、原因和采集者。
     */
    @PostMapping("/{observationId}/corrigenda")
    public ResponseEntity<CorrigendumResponse> submit(@PathVariable String observationId,
                                                      @Valid @RequestBody SubmitCorrigendumRequest request) {
        CorrigendumService.CorrOutcome outcome = corrigendumService.submit(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 批量提交更正附页：先校验所有原观测、采集者和最终坐标边界，任一失败整批不产生附页。
     */
    @PostMapping("/corrigenda/batch")
    public ResponseEntity<List<CorrigendumResponse>> submitBatch(
            @Valid @RequestBody BatchCorrigendumRequest request) {
        CorrigendumService.BatchOutcome outcome = corrigendumService.submitBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销最新有效附页：写入不可变撤销记录，恢复上一个有效版本。
     */
    @PostMapping("/{observationId}/corrigenda/revoke")
    public ResponseEntity<RevocationResponse> revoke(@PathVariable String observationId,
                                                     @Valid @RequestBody RevokeCorrigendumRequest request) {
        CorrigendumService.RevokeOutcome outcome = corrigendumService.revoke(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询附页链（全部版本，含已撤销），按附页版本升序。
     */
    @GetMapping("/{observationId}/corrigenda")
    public List<CorrigendumResponse> listCorrigenda(@PathVariable String observationId) {
        return corrigendumService.listCorrigenda(observationId);
    }

    /**
     * 查询不可变撤销记录，按撤销时刻先后排序。
     */
    @GetMapping("/{observationId}/corrigenda/revocations")
    public List<RevocationResponse> listRevocations(@PathVariable String observationId) {
        return corrigendumService.listRevocations(observationId);
    }

    /**
     * 查询待复审标记（裁决后又发生附页提交/撤销时生成）。
     */
    @GetMapping("/{observationId}/review-flags")
    public List<ReviewFlagResponse> listReviewFlags(@PathVariable String observationId) {
        return corrigendumService.listReviewFlags(observationId);
    }

    /**
     * 导出视图（冲突簇共用）：未裁决观测应用最新有效附页；已裁决观测冻结裁决版本。
     */
    @GetMapping("/{observationId}/view")
    public ObservationViewResponse view(@PathVariable String observationId) {
        return corrigendumService.view(observationId);
    }
}
