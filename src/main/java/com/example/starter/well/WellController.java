package com.example.starter.well;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地热井运行段、压力采样与模式切换接口，压力单位 MPa，时刻 UTC。
 */
@RestController
@RequestMapping("/api")
public class WellController {

    private final WellService wellService;
    private final SwitchService switchService;

    public WellController(WellService wellService, SwitchService switchService) {
        this.wellService = wellService;
        this.switchService = switchService;
    }

    /** 新建运行段请求。 */
    public record CreateSegmentBody(
            @NotBlank @Pattern(regexp = "INJECTION|PRODUCTION") String mode,
            @NotNull Instant startUtc) {
    }

    /** 采样请求。 */
    public record SampleBody(@NotBlank String requestId,
                             @NotNull BigDecimal pressureMpa,
                             @NotNull Instant sampledAt) {
    }

    /** 作废请求：追加作废标记与替代读数。 */
    public record VoidBody(@NotBlank String reason,
                           @NotBlank String replacementRequestId,
                           @NotNull BigDecimal replacementPressureMpa,
                           @NotNull Instant replacementSampledAt) {
    }

    /** 切换申请请求。 */
    public record SwitchBody(@NotBlank String requestId,
                             @NotBlank @Pattern(regexp = "INJECTION|PRODUCTION") String toMode) {
    }

    /** 审核请求：approve=true 批准（需给新段起点），false 拒绝（可给原因）。 */
    public record ReviewBody(@NotNull Boolean approve,
                             String reason,
                             Instant newSegmentStart) {
    }

    /** 时间线响应：运行段与切换申请（含审核快照与拒绝原因）。 */
    public record TimelineView(String wellNo, List<WellSegment> segments,
                               List<SwitchRequestView> switchRequests) {
    }

    /** 切换申请视图，附带审核冻结的采样快照。 */
    public record SwitchRequestView(SwitchRequest request, List<SampleSnapshot> samples) {
    }

    @PostMapping("/wells/{wellNo}/segments")
    @ResponseStatus(HttpStatus.CREATED)
    public WellSegment createSegment(@PathVariable String wellNo,
                                     @Valid @RequestBody CreateSegmentBody body) {
        return wellService.createSegment(wellNo, body.mode(), body.startUtc());
    }

    @PostMapping("/wells/{wellNo}/samples")
    @ResponseStatus(HttpStatus.CREATED)
    public PressureSample addSample(@PathVariable String wellNo,
                                    @Valid @RequestBody SampleBody body) {
        return wellService.addSample(wellNo, body.requestId(), body.pressureMpa(), body.sampledAt());
    }

    @PostMapping("/samples/{sampleId}/void")
    public PressureSample voidSample(@PathVariable long sampleId,
                                     @Valid @RequestBody VoidBody body) {
        return wellService.voidSample(sampleId, body.reason(), body.replacementRequestId(),
                body.replacementPressureMpa(), body.replacementSampledAt());
    }

    @PostMapping("/wells/{wellNo}/switch-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public SwitchRequest applySwitch(@PathVariable String wellNo,
                                     @Valid @RequestBody SwitchBody body) {
        return switchService.apply(wellNo, body.requestId(), body.toMode());
    }

    @PostMapping("/switch-requests/{requestId}/review")
    public SwitchRequest review(@PathVariable long requestId,
                                @Valid @RequestBody ReviewBody body) {
        return switchService.review(requestId, body.approve(), body.reason(), body.newSegmentStart());
    }

    @GetMapping("/wells/{wellNo}/timeline")
    public TimelineView timeline(@PathVariable String wellNo) {
        List<SwitchRequestView> requests = switchService.requestsOf(wellNo).stream()
                .map(request -> new SwitchRequestView(request, switchService.snapshotsOf(request.id())))
                .toList();
        return new TimelineView(wellNo, wellService.timeline(wellNo), requests);
    }
}