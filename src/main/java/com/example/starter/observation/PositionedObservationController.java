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
 * 观测点（新类型观测）API：创建、离线提交（坐标与矩阵作为完整字段组的三方合并）、删除、
 * 当前/历史版本查询，以及引用两条观测点当前版本的一致性确认与确认历史查询。
 */
@RestController
@RequestMapping("/api/positioned-observations")
@Validated
public class PositionedObservationController {

    private final PositionedObservationService positionedObservationService;

    public PositionedObservationController(PositionedObservationService positionedObservationService) {
        this.positionedObservationService = positionedObservationService;
    }

    /**
     * 创建观测点，初始版本为 1。
     */
    @PostMapping
    public ResponseEntity<PositionedObservationResponse> create(
            @Valid @RequestBody CreatePositionedObservationRequest request) {
        PositionedObservationService.PositionedWriteOutcome outcome = positionedObservationService.create(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 离线提交：携带 baseVersion 与坐标矩阵字段组的完整候选值，执行三方合并。
     */
    @PostMapping("/{observationId}/merge")
    public ResponseEntity<PositionedObservationResponse> merge(
            @PathVariable String observationId,
            @Valid @RequestBody MergePositionedObservationRequest request) {
        PositionedObservationService.PositionedWriteOutcome outcome =
                positionedObservationService.merge(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 删除观测点：expectedVersion 匹配当前版本时生成新版本墓碑。
     */
    @PostMapping("/{observationId}/delete")
    public ResponseEntity<PositionedObservationResponse> delete(
            @PathVariable String observationId,
            @Valid @RequestBody DeletePositionedObservationRequest request) {
        PositionedObservationService.PositionedWriteOutcome outcome =
                positionedObservationService.delete(observationId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询当前内容；墓碑只返回删除状态和版本。
     */
    @GetMapping("/{observationId}")
    public PositionedObservationResponse getCurrent(@PathVariable String observationId) {
        return positionedObservationService.getCurrent(observationId);
    }

    /**
     * 查询指定历史版本；墓碑版本只返回删除状态和版本。
     */
    @GetMapping("/{observationId}/versions/{version}")
    public PositionedObservationResponse getVersion(@PathVariable String observationId,
                                                    @PathVariable @Min(1) int version) {
        return positionedObservationService.getVersion(observationId, version);
    }

    /**
     * 一致性确认：引用两条不同观测点的当前版本，按坐标差与协方差矩阵和判定位置差异是否可接受。
     */
    @PostMapping("/confirmations")
    public ResponseEntity<ConfirmationResponse> confirm(
            @Valid @RequestBody ConfirmConsistencyRequest request) {
        PositionedObservationService.ConfirmOutcome outcome = positionedObservationService.confirm(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 按全局唯一 confirmationId 查询不可变确认记录。
     */
    @GetMapping("/confirmations/{confirmationId}")
    public ConfirmationResponse getConfirmation(@PathVariable String confirmationId) {
        return ConfirmationResponse.of(positionedObservationService.getConfirmation(confirmationId));
    }

    /**
     * 按观测点标识查询其参与的确认历史，按确认时刻先后排序。
     */
    @GetMapping("/{observationId}/confirmations")
    public List<ConfirmationResponse> listConfirmations(@PathVariable String observationId) {
        return positionedObservationService.listConfirmations(observationId).stream()
                .map(ConfirmationResponse::of)
                .toList();
    }
}
