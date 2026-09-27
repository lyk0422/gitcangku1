package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CompatibilityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备模块清单上报与查询。上报为全量替换并按提交顺序裁决，历史任务不被改写。
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/modules")
public class DeviceModulesController {

    private final CompatibilityService compatibilityService;

    public DeviceModulesController(CompatibilityService compatibilityService) {
        this.compatibilityService = compatibilityService;
    }

    @PostMapping
    public DeviceModulesView report(@PathVariable String deviceId,
                                    @Valid @RequestBody DeviceModulesReportRequest request) {
        return compatibilityService.reportModules(deviceId, request);
    }

    @GetMapping
    public DeviceModulesView get(@PathVariable String deviceId) {
        return compatibilityService.getDeviceModules(deviceId);
    }
}
