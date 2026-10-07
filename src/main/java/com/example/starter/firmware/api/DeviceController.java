package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备登记、查询、固件拉取与启动计数维护。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final TaskService taskService;

    public DeviceController(DeviceService deviceService, TaskService taskService) {
        this.deviceService = deviceService;
        this.taskService = taskService;
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
    public PullResponse pull(@PathVariable String deviceId, @Valid @RequestBody PullRequest request) {
        return taskService.pull(deviceId, request);
    }

    @PostMapping("/{deviceId}/boot-maintenance")
    public DeviceView maintainBootCount(@PathVariable String deviceId,
                                        @Valid @RequestBody BootMaintenanceRequest request) {
        return deviceService.maintainBootCount(deviceId, request);
    }
}
