package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DeviceProfileService;
import com.example.starter.firmware.service.ProfileLaunchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备硬件画像：上报/更新、查询，以及设备端查看自己的画像投放任务。
 */
@RestController
@RequestMapping("/api/hw/devices")
public class DeviceProfileController {

    private final DeviceProfileService profileService;
    private final ProfileLaunchService launchService;

    public DeviceProfileController(DeviceProfileService profileService,
                                   ProfileLaunchService launchService) {
        this.profileService = profileService;
        this.launchService = launchService;
    }

    @PutMapping("/{deviceId}/profile")
    public DeviceProfileView upsert(@PathVariable String deviceId,
                                    @Valid @RequestBody UpsertDeviceProfileRequest request) {
        return profileService.upsert(deviceId, request);
    }

    @GetMapping("/{deviceId}/profile")
    public DeviceProfileView get(@PathVariable String deviceId) {
        return profileService.get(deviceId);
    }

    @GetMapping("/{deviceId}/tasks")
    public DeviceTasksView tasks(@PathVariable String deviceId) {
        return launchService.listDeviceTasks(deviceId);
    }
}
