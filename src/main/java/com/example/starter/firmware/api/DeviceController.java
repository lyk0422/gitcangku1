package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DeviceModuleService;
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
 * 设备登记、查询、模块清单上报与固件拉取。
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final DeviceModuleService deviceModuleService;
    private final TaskService taskService;

    public DeviceController(DeviceService deviceService, DeviceModuleService deviceModuleService,
                            TaskService taskService) {
        this.deviceService = deviceService;
        this.deviceModuleService = deviceModuleService;
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

    @PostMapping("/{deviceId}/modules")
    public DeviceModulesView reportModules(@PathVariable String deviceId,
                                           @Valid @RequestBody DeviceModuleReportRequest request) {
        return deviceModuleService.report(deviceId, request);
    }

    @GetMapping("/{deviceId}/modules")
    public DeviceModulesView modules(@PathVariable String deviceId) {
        return deviceModuleService.current(deviceId);
    }

    @GetMapping("/{deviceId}/modules/history")
    public DeviceModuleReportHistoryResponse moduleHistory(@PathVariable String deviceId) {
        return deviceModuleService.history(deviceId);
    }

    @PostMapping("/{deviceId}/pull")
    public PullResponse pull(@PathVariable String deviceId, @Valid @RequestBody RequestIdBody request) {
        return taskService.pull(deviceId, request.requestId());
    }
}
