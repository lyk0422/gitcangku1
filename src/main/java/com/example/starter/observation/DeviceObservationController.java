package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测设备时钟偏移矫正 API：偏移登记/修改/明细查询、设备观测提交、
 * 观测当前状态与版本查询、重排记录查询。
 */
@RestController
@Validated
public class DeviceObservationController {

    private final DeviceObservationService deviceObservationService;

    public DeviceObservationController(DeviceObservationService deviceObservationService) {
        this.deviceObservationService = deviceObservationService;
    }

    /**
     * 登记设备偏移记录：生效起始时刻相同即区间重叠（409）；成功后同事务重建受影响区间。
     */
    @PostMapping("/api/devices/{deviceId}/offsets")
    public ResponseEntity<OffsetResponse> registerOffset(
            @PathVariable @NotBlank @Size(max = 64) String deviceId,
            @Valid @RequestBody RegisterOffsetRequest request) {
        DeviceObservationService.OffsetOutcome outcome =
                deviceObservationService.registerOffset(deviceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 修改既有偏移记录的偏移秒数；成功后同事务重建受影响区间。
     */
    @PutMapping("/api/devices/{deviceId}/offsets")
    public ResponseEntity<OffsetResponse> modifyOffset(
            @PathVariable @NotBlank @Size(max = 64) String deviceId,
            @Valid @RequestBody ModifyOffsetRequest request) {
        DeviceObservationService.OffsetOutcome outcome =
                deviceObservationService.modifyOffset(deviceId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询设备偏移明细，按生效起始时刻升序。
     */
    @GetMapping("/api/devices/{deviceId}/offsets")
    public List<OffsetResponse> listOffsets(@PathVariable String deviceId) {
        return deviceObservationService.listOffsets(deviceId);
    }

    /**
     * 设备观测提交：携带设备标识与设备本地时刻，服务端换算矫正后时刻并保存原始本地时刻。
     */
    @PostMapping("/api/device-observations")
    public ResponseEntity<SubmitObservationResponse> submit(
            @Valid @RequestBody SubmitObservationRequest request) {
        DeviceObservationService.SubmitOutcome outcome = deviceObservationService.submit(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询观测当前状态（当前胜出版本）。
     */
    @GetMapping("/api/device-observations/{observationId}")
    public ObservationStateResponse getObservationState(@PathVariable String observationId) {
        return deviceObservationService.getObservationState(observationId);
    }

    /**
     * 查询观测全部版本（按合并顺序排位返回）。
     */
    @GetMapping("/api/device-observations/{observationId}/versions")
    public List<ObservationVersionResponse> listObservationVersions(@PathVariable String observationId) {
        return deviceObservationService.listObservationVersions(observationId);
    }

    /**
     * 查询重排记录；可选 observationId 过滤。
     */
    @GetMapping("/api/reorders")
    public List<ReorderRecord> listReorders(@RequestParam(required = false) String observationId) {
        return deviceObservationService.listReorders(observationId);
    }
}
