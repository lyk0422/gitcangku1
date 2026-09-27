package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DeviceModuleService;
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

    private final com.example.starter.firmware.service.DeviceService deviceService;
    private final DeviceModuleService deviceModuleService;
    private final TaskService taskService;
    private final com.example.starter.firmware.service.CompatibilityService compatibilityService;

    public DeviceController(com.example.starter.firmware.service.DeviceService deviceService,
                            DeviceModuleService deviceModuleService, TaskService taskService,
                            com.example.starter.firmware.service.CompatibilityService compatibilityService) {
        this.deviceService = deviceService;
        this.deviceModuleService = deviceModuleService;
        this.taskService = taskService;
        this.compatibilityService = compatibilityService;
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
     * 设备模块清单全量上报：后提交的整体覆盖此前清单，版本为点分数字，非法返回 422。
     */
    @PostMapping("/{deviceId}/modules")
    public DeviceModulesView reportModules(@PathVariable String deviceId,
                                           @Valid @RequestBody ReportDeviceModulesRequest request) {
        return deviceModuleService.report(deviceId, request);
    }

    @GetMapping("/{deviceId}/modules")
    public DeviceModulesView getModules(@PathVariable String deviceId) {
        return deviceModuleService.get(deviceId);
    }

    /**
     * 设备维度兼容判定历史诊断（只读，不修改状态），按提交顺序返回。
     */
    @GetMapping("/{deviceId}/checks")
    public CheckHistoryResponse checks(@PathVariable String deviceId) {
        deviceService.get(deviceId);
        return new CheckHistoryResponse(compatibilityService.listByDevice(deviceId).stream()
                .map(CheckView::of).toList());
    }
}
