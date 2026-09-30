package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DeviceProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备硬件画像写入（首次/更新，乐观版本号）与当前版本查询。画像只追加版本，不追改历史。
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/profile")
public class DeviceProfileController {

    private final DeviceProfileService service;

    public DeviceProfileController(DeviceProfileService service) {
        this.service = service;
    }

    @PutMapping
    public ProfileView upsert(@PathVariable String deviceId, @Valid @RequestBody UpsertDeviceProfileRequest request) {
        return service.upsert(deviceId, request);
    }

    @GetMapping
    public ProfileView get(@PathVariable String deviceId) {
        return service.get(deviceId);
    }
}
