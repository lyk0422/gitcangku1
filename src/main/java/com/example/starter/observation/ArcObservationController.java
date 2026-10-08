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

/**
 * 圆弧观测 API：创建、离线提交（整组三方合并）、圆周区间裁定与裁定记录查询。
 * 当前/历史版本查询与删除沿用 /api/observations 通用端点。
 */
@RestController
@RequestMapping("/api/observations/arcs")
@Validated
public class ArcObservationController {

    private final ArcObservationService arcObservationService;

    public ArcObservationController(ArcObservationService arcObservationService) {
        this.arcObservationService = arcObservationService;
    }

    /**
     * 创建圆弧观测记录，初始版本为 1。
     */
    @PostMapping
    public ResponseEntity<ObservationResponse> create(@Valid @RequestBody CreateArcObservationRequest request) {
        ObservationService.WriteOutcome outcome = arcObservationService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 圆弧离线提交：携带 baseVersion 与圆弧完整字段组候选值，执行整组三方合并。
     */
    @PostMapping("/{observationId}/merge")
    public ResponseEntity<ObservationResponse> merge(@PathVariable String observationId,
                                                     @Valid @RequestBody MergeArcObservationRequest request) {
        ObservationService.WriteOutcome outcome = arcObservationService.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 圆周区间裁定：引用 2 至 10 条圆弧观测的当前版本，求全部圆弧交集并落库不可变裁定记录。
     */
    @PostMapping("/adjudications")
    public ResponseEntity<ArcAdjudicationResponse> adjudicate(
            @Valid @RequestBody AdjudicateArcsRequest request) {
        ArcObservationService.AdjudicateOutcome outcome = arcObservationService.adjudicate(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按全局唯一 adjudicationId 查询不可变裁定记录。
     */
    @GetMapping("/adjudications/{adjudicationId}")
    public ArcAdjudicationResponse getAdjudication(@PathVariable String adjudicationId) {
        return ArcAdjudicationResponse.of(arcObservationService.getAdjudication(adjudicationId));
    }
}
