package com.example.starter.crosssection;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 断面观测与水域面积成果 API：断面点整组版本化（创建/离线合并/删除/冲突裁决/查询）
 * 与断面成果（创建/查询）。几何面积由精确分数积分得出，不使用采样计数或通用报表。
 */
@RestController
@RequestMapping("/api/cross-sections")
@Validated
public class CrossSectionController {

    private final CrossSectionService service;
    private final ObjectMapper objectMapper;

    public CrossSectionController(CrossSectionService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建断面观测点，断面号/横向/高程作为完整版本组，初始版本为 1。
     */
    @PostMapping("/points")
    public ResponseEntity<CrossSectionResponse> createPoint(
            @Valid @RequestBody CreateCrossSectionRequest request) {
        CrossSectionService.WriteOutcome outcome = service.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 断面点离线提交：携带 baseVersion 与完整候选组执行三方合并。
     */
    @PostMapping("/points/{observationId}/merge")
    public ResponseEntity<CrossSectionResponse> mergePoint(
            @PathVariable String observationId,
            @Valid @RequestBody MergeCrossSectionRequest request) {
        CrossSectionService.WriteOutcome outcome = service.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除断面观测点：expectedVersion 匹配当前版本时生成新版本墓碑。
     */
    @PostMapping("/points/{observationId}/delete")
    public ResponseEntity<CrossSectionResponse> deletePoint(
            @PathVariable String observationId,
            @Valid @RequestBody DeleteCrossSectionRequest request) {
        CrossSectionService.WriteOutcome outcome = service.delete(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询断面点当前内容；墓碑只返回删除状态和版本。
     */
    @GetMapping("/points/{observationId}")
    public CrossSectionResponse getPoint(@PathVariable String observationId) {
        return service.getCurrent(observationId);
    }

    /**
     * 查询断面点指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @GetMapping("/points/{observationId}/versions/{version}")
    public CrossSectionResponse getPointVersion(@PathVariable String observationId,
                                                @PathVariable @Min(1) int version) {
        return service.getVersion(observationId, version);
    }

    /**
     * 查询未解决冲突标记；无标记返回 404，存在时返回标记内容。
     */
    @GetMapping("/points/{observationId}/pending-conflict")
    public PendingConflict getPendingConflict(@PathVariable String observationId) {
        PendingConflict conflict = service.getPendingConflict(observationId);
        if (conflict == null) {
            throw CrossSectionApiException.notFound(
                    "no pending conflict for cross section point: " + observationId);
        }
        return conflict;
    }

    /**
     * 显式冲突裁决：提交完整候选组与冲突字段选择，原子生成新版本（或无变化）与不可变裁决记录。
     */
    @PostMapping("/points/{observationId}/resolve")
    public ResponseEntity<CrossSectionResolutionResponse> resolvePoint(
            @PathVariable String observationId,
            @Valid @RequestBody ResolveCrossSectionRequest request) {
        CrossSectionService.ResolveOutcome outcome = service.resolveConflict(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按全局唯一 resolutionId 查询裁决记录。
     */
    @GetMapping("/resolutions/{resolutionId}")
    public CrossSectionResolutionResponse getResolution(@PathVariable String resolutionId) {
        CrossSectionResolutionRecord record = service.getResolution(resolutionId);
        CrossSectionSnapshot pointed = service.getVersionSnapshotForResolution(
                record.observationId(), record.newVersion());
        return CrossSectionResolutionResponse.of(record, pointed, objectMapper);
    }

    /**
     * 创建断面水域面积成果：引用同断面 2 至 10 个当前版本点与整数水面高程 H。
     */
    @PostMapping("/results")
    public ResponseEntity<CrossSectionResultResponse> createResult(
            @Valid @RequestBody CreateCrossSectionResultRequest request) {
        CrossSectionService.ResultOutcome outcome = service.createResult(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询成果：返回冻结点列、H 与精确面积；源已更新仅作提示，不重算。
     */
    @GetMapping("/results/{resultId}")
    public CrossSectionResultResponse getResult(@PathVariable String resultId) {
        return service.getResult(resultId);
    }
}
