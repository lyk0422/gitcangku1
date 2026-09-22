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

import java.util.List;

/**
 * 现场观测离线合并 API：创建、离线提交（三方合并）、冲突解决、删除、当前/历史版本与解决历史查询。
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
        ObservationService.WriteOutcome<ObservationResponse> outcome = observationService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 离线提交：携带 baseVersion 与三个字段完整候选值，执行三方合并。
     */
    @PostMapping("/{observationId}/merge")
    public ResponseEntity<ObservationResponse> merge(@PathVariable String observationId,
                                                     @Valid @RequestBody MergeObservationRequest request) {
        ObservationService.WriteOutcome<ObservationResponse> outcome =
                observationService.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 冲突解决提交：重新计算冲突后按人工选择落定，原子生成新观测版本与不可变解决记录。
     */
    @PostMapping("/{observationId}/resolve")
    public ResponseEntity<ResolutionResponse> resolve(@PathVariable String observationId,
                                                      @Valid @RequestBody ResolveObservationRequest request) {
        ObservationService.WriteOutcome<ResolutionResponse> outcome =
                observationService.resolve(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除观测记录：expectedVersion 匹配当前版本时生成新版本墓碑。
     */
    @PostMapping("/{observationId}/delete")
    public ResponseEntity<ObservationResponse> delete(@PathVariable String observationId,
                                                      @Valid @RequestBody DeleteObservationRequest request) {
        ObservationService.WriteOutcome<ObservationResponse> outcome =
                observationService.delete(observationId, request);
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

    /**
     * 按解决标识查询解决记录；不存在返回 404。
     */
    @GetMapping("/resolutions/{resolutionId}")
    public ResolutionResponse getResolution(@PathVariable String resolutionId) {
        return observationService.getResolution(resolutionId);
    }

    /**
     * 按观测记录查询解决历史，按解决时刻升序。
     */
    @GetMapping("/{observationId}/resolutions")
    public List<ResolutionResponse> listResolutions(@PathVariable String observationId) {
        return observationService.listResolutions(observationId);
    }
}
