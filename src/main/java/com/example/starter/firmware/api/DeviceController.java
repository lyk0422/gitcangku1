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
 * 设备登记、查询与固件拉取。
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

    /**
     * 设备隔离查询：本轮连续失败次数、当前隔离状态、隔离依据（两次任务/版本/原因/确认人）与解除记录。
     */
    @GetMapping("/{deviceId}/quarantine")
    public DeviceQuarantineView quarantine(@PathVariable String deviceId) {
        return quarantineService.getQuarantine(deviceId);
    }

    /**
     * 人工解除隔离：审核人必须不同于隔离确认人；解除只开启新一轮投放，不清除历史失败次数。
     */
    @PostMapping("/{deviceId}/quarantine/release")
    public DeviceQuarantineView releaseQuarantine(@PathVariable String deviceId,
                                                  @Valid @RequestBody ReleaseQuarantineRequest request) {
        return quarantineService.release(deviceId, request);
    }
}
