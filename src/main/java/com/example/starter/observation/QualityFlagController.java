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
 * 观测质量标记 API（骨架，逐方法填充）：标记创建、复核、标记历史、置信度轨迹、待复核清单。
 */
@RestController
@RequestMapping("/api/observations")
@Validated
public class QualityFlagController {

    private final QualityFlagService qualityFlagService;

    public QualityFlagController(QualityFlagService qualityFlagService) {
        this.qualityFlagService = qualityFlagService;
    }

    @PostMapping("/{observationId}/flags")
    public ResponseEntity<Object> createFlag(@PathVariable String observationId,
                                             @Valid @RequestBody CreateQualityFlagRequest request) {
        QualityFlagService.FlagWriteOutcome outcome = qualityFlagService.createFlag(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/flags/{flagKey}/reviews")
    public ResponseEntity<Object> reviewFlag(@PathVariable String flagKey,
                                             @Valid @RequestBody ReviewQualityFlagRequest request) {
        QualityFlagService.FlagWriteOutcome outcome = qualityFlagService.reviewFlag(flagKey, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @GetMapping("/{observationId}/flags")
    public List<QualityFlagResponse> getFlagHistory(@PathVariable String observationId) {
        return qualityFlagService.getFlagHistory(observationId);
    }

    @GetMapping("/{observationId}/confidence-trail")
    public List<ConfidencePoint> getConfidenceTrail(@PathVariable String observationId) {
        return qualityFlagService.getConfidenceTrail(observationId);
    }

    @GetMapping("/{observationId}/pending-flags")
    public List<QualityFlagResponse> getPendingFlags(@PathVariable String observationId) {
        return qualityFlagService.getPendingFlags(observationId);
    }
}
