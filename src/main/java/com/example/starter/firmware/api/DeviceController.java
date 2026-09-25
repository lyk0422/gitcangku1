package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.QuarantineService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备登记、查询、固件拉取与异常隔离。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final TaskService taskService;
    private final QuarantineService quarantineService;

    public DeviceController(DeviceService deviceService, TaskService taskService,
                            QuarantineService quarantineService) {
        this.deviceService = deviceService;
        this.taskService = taskService;
        this.quarantineService = quarantineService;
    }

    @PostMapping
    public DeviceView register(@Valid @RequestBody RegisterDeviceRequest request) {
        return deviceService.register(request);
    }

    @GetMapping("/{deviceId}")
    public DeviceView get(@PathVariable String deviceId) {
        return deviceService.get(deviceId);
    }

    @PostMapping("/{deviceId}/pull")
    public PullResponse pull(@PathVariable String deviceId, @Valid @RequestBody RequestIdBody request) {
        return taskService.pull(deviceId, request.requestId());
    }

    @PostMapping("/{deviceId}/quarantine")
    public QuarantineRecordView quarantine(@PathVariable String deviceId,
                                           @Valid @RequestBody QuarantineRequest request) {
        return quarantineService.quarantine(deviceId, request);
    }

    @PostMapping("/{deviceId}/quarantine/release")
    public QuarantineRecordView releaseQuarantine(@PathVariable String deviceId,
                                                  @Valid @RequestBody ReleaseQuarantineRequest request) {
        return quarantineService.release(deviceId, request);
    }

    @GetMapping("/{deviceId}/quarantines")
    public QuarantineHistoryResponse quarantines(@PathVariable String deviceId) {
        return new QuarantineHistoryResponse(deviceId, quarantineService.history(deviceId));
    }

    @GetMapping("/{deviceId}/cancel-reasons")
    public CancelReasonListResponse cancelReasons(@PathVariable String deviceId) {
        return new CancelReasonListResponse(quarantineService.cancelReasons(deviceId));
    }

    @GetMapping("/{deviceId}/rejected-receipts")
    public RejectedReceiptListResponse rejectedReceipts(@PathVariable String deviceId) {
        return new RejectedReceiptListResponse(quarantineService.rejectedReceipts(deviceId));
    }
}
