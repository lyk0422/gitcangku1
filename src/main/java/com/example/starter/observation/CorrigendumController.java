package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 观测更正附页 API：提交/批量提交附页、撤销最新有效附页、附页链/撤销历史/待复审标记查询与导出视图。
 * 原始观测不可覆盖；已人工裁决的观测冻结裁决结果，新附页仅生成待复审标记。
 */
@RestController
@RequestMapping("/api/observations")
@Validated
public class CorrigendumController {

    private final CorrigendumService corrigendumService;
    private final ObjectMapper objectMapper;

    public CorrigendumController(CorrigendumService corrigendumService, ObjectMapper objectMapper) {
        this.corrigendumService = corrigendumService;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交单张更正附页：指定原观测版本、字段差异、原因与采集者；空差异或未知字段 422。
     */
    @PostMapping("/{observationId}/corrigenda")
    public ResponseEntity<CorrigendumResponse> submit(@PathVariable String observationId,
                                                      @Valid @RequestBody CorrigendumRequest request) {
        CorrigendumService.CorrOutcome outcome = corrigendumService.submit(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 批量提交附页：先校验全部原观测、采集者与最终坐标边界，任一失败整批不产生附页或重算。
     */
    @PostMapping("/corrigenda/batch")
    public ResponseEntity<List<CorrigendumResponse>> submitBatch(
            @Valid @RequestBody CorrigendumBatchRequest request) {
        CorrigendumService.CorrBatchOutcome outcome = corrigendumService.submitBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 撤销附页：只允许最新有效附页，写入不可变撤销记录并恢复上一个有效版本。
     */
    @PostMapping("/{observationId}/corrigenda/revoke")
    public ResponseEntity<RevocationRecord> revoke(@PathVariable String observationId,
                                                   @Valid @RequestBody CorrigendumRevokeRequest request) {
        CorrigendumService.CorrRevokeOutcome outcome = corrigendumService.revoke(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询附页链（含已撤销），按附页版本先后排序。
     */
    @GetMapping("/{observationId}/corrigenda")
    public List<CorrigendumResponse> listCorrigenda(@PathVariable String observationId) {
        return corrigendumService.listCorrigenda(observationId).stream()
                .map(record -> CorrigendumResponse.of(record, objectMapper))
                .toList();
    }

    /**
     * 查询撤销历史（不可变撤销记录），按撤销时刻先后排序。
     */
    @GetMapping("/{observationId}/corrigenda/revocations")
    public List<RevocationRecord> listRevocations(@PathVariable String observationId) {
        return corrigendumService.listRevocations(observationId);
    }

    /**
     * 查询待复审标记：已人工裁决的观测收到新附页时生成，裁决结果本身冻结不改写。
     */
    @GetMapping("/{observationId}/re-reviews")
    public List<ReReviewMarker> listMarkers(@PathVariable String observationId) {
        return corrigendumService.listMarkers(observationId);
    }

    /**
     * 导出视图：未裁决观测应用最新有效附页后的有效值；已裁决观测冻结裁决时采用的观测版本。
     */
    @GetMapping("/{observationId}/export")
    public ExportViewResponse exportView(@PathVariable String observationId) {
        return corrigendumService.exportView(observationId);
    }
}
