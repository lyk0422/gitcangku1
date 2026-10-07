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
 * 圆弧观测 API：创建、离线提交（整组三方合并）、人工裁决、删除、当前/历史版本查询，
 * 以及圆周区间裁定（引用 2 至 10 条当前版本求全部圆弧交集并冻结）与裁定查询。
 */
@RestController
@RequestMapping("/api/arc-observations")
@Validated
public class ArcObservationController {

    private final ArcObservationService arcObservationService;
    private final ArcAdjudicationService arcAdjudicationService;

    public ArcObservationController(ArcObservationService arcObservationService,
                                    ArcAdjudicationService arcAdjudicationService) {
        this.arcObservationService = arcObservationService;
        this.arcAdjudicationService = arcAdjudicationService;
    }

    /**
     * 创建圆弧观测记录，初始版本为 1。
     */
    @PostMapping
    public ResponseEntity<ArcObservationResponse> create(
            @Valid @RequestBody CreateArcObservationRequest request) {
        ArcObservationService.ArcWriteOutcome outcome = arcObservationService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 离线提交：携带 baseVersion 与圆弧集合完整候选值，按完整字段组三方合并。
     */
    @PostMapping("/{observationId}/merge")
    public ResponseEntity<ArcObservationResponse> merge(
            @PathVariable String observationId,
            @Valid @RequestBody MergeArcObservationRequest request) {
        ArcObservationService.ArcWriteOutcome outcome = arcObservationService.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 人工裁决：强制写入候选圆弧形集合并清除未解决冲突标记。
     */
    @PostMapping("/{observationId}/resolve")
    public ResponseEntity<ArcObservationResponse> resolve(
            @PathVariable String observationId,
            @Valid @RequestBody ResolveArcObservationRequest request) {
        ArcObservationService.ArcWriteOutcome outcome = arcObservationService.resolve(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除圆弧观测记录：expectedVersion 匹配当前版本时生成新版本墓碑。
     */
    @PostMapping("/{observationId}/delete")
    public ResponseEntity<ArcObservationResponse> delete(
            @PathVariable String observationId,
            @Valid @RequestBody DeleteArcObservationRequest request) {
        ArcObservationService.ArcWriteOutcome outcome = arcObservationService.delete(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @GetMapping("/{observationId}")
    public ArcObservationResponse getCurrent(@PathVariable String observationId) {
        return arcObservationService.getCurrent(observationId);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @GetMapping("/{observationId}/versions/{version}")
    public ArcObservationResponse getVersion(@PathVariable String observationId,
                                             @PathVariable @Min(1) int version) {
        return arcObservationService.getVersion(observationId, version);
    }

    /**
     * 圆周区间裁定：引用 2 至 10 条当前版本求全部圆弧交集，成功则冻结不可变裁定。
     */
    @PostMapping("/adjudications")
    public ResponseEntity<AdjudicationResponse> adjudicate(
            @Valid @RequestBody AdjudicateArcRequest request) {
        ArcAdjudicationService.AdjudicateOutcome outcome = arcAdjudicationService.adjudicate(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按全局唯一 adjudicationId 查询不可变裁定记录。
     */
    @GetMapping("/adjudications/{adjudicationId}")
    public AdjudicationResponse getAdjudication(@PathVariable String adjudicationId) {
        return AdjudicationResponse.of(arcAdjudicationService.getAdjudication(adjudicationId));
    }
}
