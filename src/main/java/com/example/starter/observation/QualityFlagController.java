package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测质量标记 API：标记创建、复核、标记历史、待复核清单与置信度轨迹查询。
 */
@RestController
@RequestMapping("/api/observations")
public class QualityFlagController {

    private final QualityFlagService qualityFlagService;

    public QualityFlagController(QualityFlagService qualityFlagService) {
        this.qualityFlagService = qualityFlagService;
    }

    /**
     * 创建待复核质量标记：附加于观测当前版本，不改变观测内容与版本。
     */
    @PostMapping("/{observationId}/flags")
    public ResponseEntity<Object> createFlag(@PathVariable String observationId,
                                             @Valid @RequestBody CreateQualityFlagRequest request) {
        QualityFlagService.FlagOutcome outcome = qualityFlagService.createFlag(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 复核质量标记：由与提交人不同的角色给出 CONFIRMED / DISMISSED 结论与理由。
     */
    @PostMapping("/{observationId}/flags/review")
    public ResponseEntity<Object> review(@PathVariable String observationId,
                                         @Valid @RequestBody ReviewQualityFlagRequest request) {
        QualityFlagService.FlagOutcome outcome = qualityFlagService.review(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询某观测的标记历史（含复核记录）。
     */
    @GetMapping("/{observationId}/flags")
    public List<QualityFlagResponse> listFlags(@PathVariable String observationId) {
        return qualityFlagService.listFlags(observationId);
    }

    /**
     * 查询某观测的待复核标记清单。
     */
    @GetMapping("/{observationId}/flags/pending")
    public List<QualityFlagResponse> listPendingFlags(@PathVariable String observationId) {
        return qualityFlagService.listPendingFlags(observationId);
    }

    /**
     * 查询某观测的置信度轨迹：各版本快照对应的置信度。
     */
    @GetMapping("/{observationId}/confidence")
    public List<ConfidencePoint> confidenceTrajectory(@PathVariable String observationId) {
        return qualityFlagService.confidenceTrajectory(observationId);
    }
}
