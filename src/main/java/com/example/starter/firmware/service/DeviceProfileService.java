package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ProfileView;
import com.example.starter.firmware.api.UpsertDeviceProfileRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ProfileSnapshot;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceProfileRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备硬件画像写入与查询。画像只追加版本：首次提交 expectedVersion=0，之后更新须带当前版本号，
 * 版本不匹配返回 409。画像更新与任务创建都先锁 device 行，保证任务只引用一个完整画像版本，
 * 且画像修改只产生新版本，不追改已下发任务的目标、版本或统计口径。
 */
@Service
public class DeviceProfileService {

    private final DeviceRepository deviceRepository;
    private final DeviceProfileRepository profileRepository;
    private final IdempotencyService idempotency;

    public DeviceProfileService(DeviceRepository deviceRepository, DeviceProfileRepository profileRepository,
                                IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.profileRepository = profileRepository;
        this.idempotency = idempotency;
    }

    public ProfileView upsert(String deviceId, UpsertDeviceProfileRequest request) {
        List<String> tags = request.effectiveTags().stream().distinct().sorted().toList();
        String fingerprint = String.join("|", "device.profile.upsert", deviceId,
                String.valueOf(request.expectedVersionOrZero()), String.valueOf(request.model()),
                String.valueOf(request.memoryMb()), String.join(",", tags));
        return idempotency.execute(request.requestId(), "device.profile.upsert", fingerprint, () -> {
            // 锁设备行：与任务创建互斥，使每次画像读取都落在一个完整版本上
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            int expectedVersion = request.expectedVersionOrZero();
            Integer currentVersion = profileRepository.currentVersionNumber(device.deviceId()).orElse(null);
            int newVersion;
            if (currentVersion == null) {
                if (expectedVersion != 0) {
                    throw ApiException.conflict("PROFILE_VERSION_CONFLICT",
                            "设备尚无画像，首次提交 expectedVersion 应为 0");
                }
                newVersion = 1;
                profileRepository.insertFirstVersion(device.deviceId(), request.model(),
                        request.memoryMb(), tags);
            } else {
                if (expectedVersion == 0) {
                    throw ApiException.conflict("PROFILE_VERSION_CONFLICT",
                            "设备已存在画像版本 " + currentVersion + "，更新须带当前版本号");
                }
                if (currentVersion != expectedVersion) {
                    throw ApiException.conflict("PROFILE_VERSION_CONFLICT",
                            "画像版本已变化，当前版本: " + currentVersion + "，请求期望: " + expectedVersion);
                }
                newVersion = currentVersion + 1;
                if (profileRepository.appendVersion(device.deviceId(), currentVersion, request.model(),
                        request.memoryMb(), tags) != 1) {
                    throw new IllegalStateException("画像版本追加失败: " + deviceId);
                }
            }
            ProfileSnapshot snapshot = profileRepository.findVersion(device.deviceId(), newVersion)
                    .orElseThrow(() -> new IllegalStateException("画像新版本读取失败"));
            return ProfileView.of(snapshot);
        }, ProfileView.class);
    }

    public ProfileView get(String deviceId) {
        return ProfileView.of(findCurrentProfile(deviceId));
    }

    public ProfileSnapshot findCurrentProfile(String deviceId) {
        return profileRepository.findCurrent(deviceId)
                .orElseThrow(() -> ApiException.notFound("PROFILE_NOT_FOUND",
                        "设备尚未提交硬件画像: " + deviceId));
    }
}
