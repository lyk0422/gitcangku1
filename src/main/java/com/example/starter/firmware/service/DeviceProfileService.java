package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceProfileView;
import com.example.starter.firmware.api.UpsertDeviceProfileRequest;
import com.example.starter.firmware.domain.DeviceProfile;
import com.example.starter.firmware.domain.ProfileCompatibility;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceProfileRepository;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 设备硬件画像上报与查询。画像版本每次成功更新加一；
 * 画像修改只影响后续新任务，不追改已下发任务的目标、版本或统计口径。
 */
@Service
public class DeviceProfileService {

    private final DeviceProfileRepository profileRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;

    public DeviceProfileService(DeviceProfileRepository profileRepository, DeviceService deviceService,
                                IdempotencyService idempotency) {
        this.profileRepository = profileRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
    }

    /**
     * 上报或更新画像：未上报过则插入（版本从1开始），已存在则整体覆盖并版本加一。
     * 字段缺省（null）表示设备缺少该画像字段，筛选时按不兼容处理。
     */
    public DeviceProfileView upsert(String deviceId, UpsertDeviceProfileRequest request) {
        deviceService.findDevice(deviceId);
        Set<String> tags = request.tags() == null ? null
                : ProfileCompatibility.normalizeSet(request.tags());
        String fingerprint = String.join("|", "device.profile.upsert", deviceId,
                String.valueOf(request.model()), String.valueOf(request.memoryMb()),
                tags == null ? "null" : ProfileCompatibility.join(tags));
        return idempotency.execute(request.requestId(), "device.profile.upsert", fingerprint, () -> {
            if (profileRepository.findById(deviceId).isEmpty()) {
                profileRepository.insert(deviceId, request.model(), request.memoryMb(), tags);
            } else {
                profileRepository.update(deviceId, request.model(), request.memoryMb(), tags);
            }
            return DeviceProfileView.of(findProfile(deviceId));
        }, DeviceProfileView.class);
    }

    public DeviceProfileView get(String deviceId) {
        deviceService.findDevice(deviceId);
        return DeviceProfileView.of(findProfile(deviceId));
    }

    public DeviceProfile findProfile(String deviceId) {
        return profileRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("PROFILE_NOT_FOUND", "设备无画像: " + deviceId));
    }
}
