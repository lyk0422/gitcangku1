package com.example.starter.web;

import com.example.starter.domain.PressureSample;
import com.example.starter.domain.RunMode;
import com.example.starter.domain.RunSegment;
import com.example.starter.domain.SwitchRequest;
import com.example.starter.service.TimelineView;
import com.example.starter.service.VoidResult;
import com.example.starter.service.WellService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地热井运行段、压力采样与切换审核接口。
 */
@RestController
@RequestMapping("/api")
public class WellController {

    private final WellService wellService;

    public WellController(WellService wellService) {
        this.wellService = wellService;
    }

    /** 创建运行段（井号绑定注入或采出模式，起点 UTC）。 */
    @PostMapping("/wells/{wellNo}/run-segments")
    public ResponseEntity<RunSegment> createSegment(@PathVariable String wellNo,
            @Valid @RequestBody CreateSegmentBody body) {
        RunSegment segment = wellService.createSegment(wellNo, body.requestId(), body.mode(), body.startTime());
        return ResponseEntity.status(201).body(segment);
    }

    /** 追加井口压力采样（MPa，UTC）。 */
    @PostMapping("/wells/{wellNo}/samples")
    public ResponseEntity<PressureSample> addSample(@PathVariable String wellNo,
            @Valid @RequestBody AddSampleBody body) {
        PressureSample sample = wellService.addSample(
                wellNo, body.requestId(), body.pressureMpa(), body.sampledAt());
        return ResponseEntity.status(201).body(sample);
    }

    /** 作废错误读数并追加替代读数。 */
    @PostMapping("/wells/{wellNo}/samples/{sampleId}/void")
    public VoidResult voidSample(@PathVariable String wellNo, @PathVariable long sampleId,
            @Valid @RequestBody VoidSampleBody body) {
        return wellService.voidSample(wellNo, sampleId, body.requestId(), body.reason(),
                body.replacementPressureMpa(), body.replacementSampledAt());
    }

    /** 申请注入/采出切换；条件不满足返回 422 且旧模式保持有效。 */
    @PostMapping("/wells/{wellNo}/switch-requests")
    public ResponseEntity<SwitchRequest> createSwitchRequest(@PathVariable String wellNo,
            @Valid @RequestBody CreateSwitchRequestBody body) {
        SwitchRequest request = wellService.createSwitchRequest(wellNo, body.requestId(), body.targetMode());
        return ResponseEntity.status(201).body(request);
    }

    /** 审核切换申请；批准后冻结采样快照与新模式起点。 */
    @PostMapping("/switch-requests/{switchRequestId}/decisions")
    public SwitchRequest decide(@PathVariable long switchRequestId,
            @Valid @RequestBody DecisionBody body) {
        return wellService.decide(switchRequestId, body.requestId(), body.approve(), body.reason());
    }

    /** 查询井时间线：运行段、每次审核冻结的采样与拒绝原因。 */
    @GetMapping("/wells/{wellNo}/timeline")
    public TimelineView timeline(@PathVariable String wellNo) {
        return wellService.timeline(wellNo);
    }

    /** 创建运行段请求体。 */
    public record CreateSegmentBody(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull RunMode mode,
            @NotNull Instant startTime) {
    }

    /** 追加采样请求体。 */
    public record AddSampleBody(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal pressureMpa,
            @NotNull Instant sampledAt) {
    }

    /** 作废采样请求体。 */
    public record VoidSampleBody(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 255) String reason,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal replacementPressureMpa,
            @NotNull Instant replacementSampledAt) {
    }

    /** 创建切换申请请求体。 */
    public record CreateSwitchRequestBody(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull RunMode targetMode) {
    }

    /** 审核决定请求体。 */
    public record DecisionBody(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull Boolean approve,
            @Size(max = 255) String reason) {
    }
}