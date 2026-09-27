package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.DeviceTrustService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备登记、查询、信任集合维护与固件拉取。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final DeviceTrustService deviceTrustService;
    private final TaskService taskService;

    public DeviceController(DeviceService deviceService, DeviceTrustService deviceTrustService,
                            TaskService taskService) {
        this.deviceService = deviceService;
        this.deviceTrustService = deviceTrustService;
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
    public PullResponse pull(@PathVariable String deviceId, @Valid @RequestBody RequestIdBody request) {
        return taskService.pull(deviceId, request.requestId());
    }

    @PutMapping("/{deviceId}/trust")
    public DeviceTrustView putTrust(@PathVariable String deviceId,
                                    @Valid @RequestBody DeviceTrustRequest request) {
        return deviceTrustService.put(deviceId, request);
    }

    @GetMapping("/{deviceId}/trust")
    public DeviceTrustView getTrust(@PathVariable String deviceId) {
        return deviceTrustService.get(deviceId);
    }
}
