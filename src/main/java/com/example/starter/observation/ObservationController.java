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
 * 现场观测离线合并 API：创建、离线提交（三方合并）、删除、当前/历史版本查询。
 */
@RestController
@RequestMapping("/api/observations")
@Validated
public class ObservationController {

    private final ObservationService observationService;

    public ObservationController(ObservationService observationService) {
        this.observationService = observationService;
    }

    /**
     * 创建观测记录，初始版本为 1。
     */
    @PostMapping
    public ResponseEntity<ObservationResponse> create(@Valid @RequestBody CreateObservationRequest request) {
        ObservationService.WriteOutcome outcome = observationService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 离线提交：携带 baseVersion 与三个字段完整候选值，执行三方合并。
     */
    @PostMapping("/{observationId}/merge")
    public ResponseEntity<ObservationResponse> merge(@PathVariable String observationId,
                                                     @Valid @RequestBody MergeObservationRequest request) {
        ObservationService.WriteOutcome outcome = observationService.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除观测记录：expectedVersion 匹配当前版本时生成新版本墓碑。
     */
    @PostMapping("/{observationId}/delete")
    public ResponseEntity<ObservationResponse> delete(@PathVariable String observationId,
                                                      @Valid @RequestBody DeleteObservationRequest request) {
        ObservationService.WriteOutcome outcome = observationService.delete(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @GetMapping("/{observationId}")
    public ObservationResponse getCurrent(@PathVariable String observationId) {
        return observationService.getCurrent(observationId);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @GetMapping("/{observationId}/versions/{version}")
    public ObservationResponse getVersion(@PathVariable String observationId,
                                          @PathVariable @Min(1) int version) {
        return observationService.getVersion(observationId, version);
    }
}
