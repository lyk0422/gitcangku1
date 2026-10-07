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
import org.springframework.web.bind.annotation.RestController;

/**
 * 4x4 二值栅格观测离线合并 API：创建、离线三方合并（连通门禁）、删除墓碑、当前/历史版本查询。
 * 与普通字段观测（/api/observations）互不覆盖：独立资源、独立表、独立裁决，但共用全局 requestId 幂等表。
 */
@RestController
@RequestMapping("/api/mask-observations")
@Validated
public class MaskObservationController {

    private final MaskObservationService maskObservationService;

    public MaskObservationController(MaskObservationService maskObservationService) {
        this.maskObservationService = maskObservationService;
    }

    /**
     * 创建栅格观测，保存完整 16 格初始图，初始版本为 1。
     */
    @PostMapping
    public ResponseEntity<MaskResponse> create(@Valid @RequestBody CreateMaskRequest request) {
        MaskObservationService.MaskOutcome outcome = maskObservationService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 离线合并：客户端基于 baseVersion 提交完整图，服务端重算逐格三方合并并做四邻接连通核验。
     */
    @PostMapping("/{observationId}/merge")
    public ResponseEntity<MaskResponse> merge(@PathVariable String observationId,
                                              @Valid @RequestBody MergeMaskRequest request) {
        MaskObservationService.MaskOutcome outcome = maskObservationService.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除栅格观测：expectedVersion 匹配当前版本时生成新版本墓碑，删除与合并走同一连通/版本门禁。
     */
    @PostMapping("/{observationId}/delete")
    public ResponseEntity<MaskResponse> delete(@PathVariable String observationId,
                                               @Valid @RequestBody DeleteMaskRequest request) {
        MaskObservationService.MaskOutcome outcome = maskObservationService.delete(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询当前完整图；墓碑只返回删除状态和版本。
     */
    @GetMapping("/{observationId}")
    public MaskResponse getCurrent(@PathVariable String observationId) {
        return maskObservationService.getCurrent(observationId);
    }

    /**
     * 查询指定历史版本完整图；墓碑版本只返回删除状态和版本。
     */
    @GetMapping("/{observationId}/versions/{version}")
    public MaskResponse getVersion(@PathVariable String observationId,
                                   @PathVariable @Min(1) int version) {
        return maskObservationService.getVersion(observationId, version);
    }
}
