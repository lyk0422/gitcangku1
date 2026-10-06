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
 * 分钟观测 API：新类型观测（站号 + UTC 整分钟时刻 + 十进制读数）的创建、
 * 离线提交（三方合并）、删除、当前/历史版本查询。
 */
@RestController
@RequestMapping("/api/minute-observations")
@Validated
public class MinuteObservationController {

    private final MinuteObservationService minuteObservationService;

    public MinuteObservationController(MinuteObservationService minuteObservationService) {
        this.minuteObservationService = minuteObservationService;
    }

    /**
     * 创建分钟观测记录，初始版本为 1；同站同分钟唯一。
     */
    @PostMapping
    public ResponseEntity<MinuteObservationResponse> create(
            @Valid @RequestBody CreateMinuteObservationRequest request) {
        MinuteObservationService.WriteOutcome outcome = minuteObservationService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 离线提交：携带 baseVersion 与三个字段完整候选值，执行三方合并。
     */
    @PostMapping("/{observationId}/merge")
    public ResponseEntity<MinuteObservationResponse> merge(
            @PathVariable String observationId,
            @Valid @RequestBody MergeMinuteObservationRequest request) {
        MinuteObservationService.WriteOutcome outcome = minuteObservationService.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除分钟观测记录：expectedVersion 匹配当前版本时生成新版本墓碑。
     */
    @PostMapping("/{observationId}/delete")
    public ResponseEntity<MinuteObservationResponse> delete(
            @PathVariable String observationId,
            @Valid @RequestBody DeleteMinuteObservationRequest request) {
        MinuteObservationService.WriteOutcome outcome = minuteObservationService.delete(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @GetMapping("/{observationId}")
    public MinuteObservationResponse getCurrent(@PathVariable String observationId) {
        return minuteObservationService.getCurrent(observationId);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @GetMapping("/{observationId}/versions/{version}")
    public MinuteObservationResponse getVersion(@PathVariable String observationId,
                                                @PathVariable @Min(1) int version) {
        return minuteObservationService.getVersion(observationId, version);
    }
}
