package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.MaintainSpaceRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.StagingReservation;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.StagingReservationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记、空间档案维护与查询。型号与分桶号登记后不可修改；
 * 空间档案版本从1开始，维护与任务首次终结加一。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final StagingReservationRepository stagingReservationRepository;
    private final IdempotencyService idempotency;

    public DeviceService(DeviceRepository deviceRepository,
                         StagingReservationRepository stagingReservationRepository,
                         IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.stagingReservationRepository = stagingReservationRepository;
        this.idempotency = idempotency;
    }

    public DeviceView register(RegisterDeviceRequest request) {
        validateSpaceProfile(request.totalSpaceBytes(), request.currentImageBytes());
        String fingerprint = RequestFingerprint.of(
                "api", "device.register",
                "deviceId", request.deviceId(),
                "model", request.model(),
                "currentVersion", request.currentVersion(),
                "bucketNo", request.bucketNo(),
                "totalSpaceBytes", request.totalSpaceBytes(),
                "currentImageBytes", request.currentImageBytes());
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), request.bucketNo(), request.totalSpaceBytes(),
                        request.currentImageBytes(), 1));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return viewOf(findDevice(request.deviceId()));
        }, DeviceView.class);
    }

    /**
     * 维护空间档案：锁定设备行后校验期望版本与容量下限（当前镜像加活跃预留），
     * 通过则更新总空间与当前镜像并版本加一；冲突响应携带当前档案版本。
     */
    public DeviceView maintainSpace(String deviceId, MaintainSpaceRequest request) {
        if (request.currentImageBytes() > request.totalSpaceBytes()) {
            throw ApiException.badRequest("IMAGE_EXCEEDS_TOTAL", "当前镜像字节数不能大于总空间");
        }
        String fingerprint = RequestFingerprint.of(
                "api", "device.space.maintain",
                "operator", request.operator(),
                "deviceId", deviceId,
                "totalSpaceBytes", request.totalSpaceBytes(),
                "currentImageBytes", request.currentImageBytes(),
                "expectedDeviceVersion", request.expectedDeviceVersion());
        return idempotency.execute(request.requestId(), "device.space.maintain", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.spaceVersion() != request.expectedDeviceVersion()) {
                throw ApiException.deviceVersionConflict("DEVICE_VERSION_CONFLICT",
                        "expectedDeviceVersion 与当前空间档案版本不一致: " + device.spaceVersion(),
                        device.spaceVersion());
            }
            long reserved = reservedBytes(deviceId);
            if (request.totalSpaceBytes() < request.currentImageBytes() + reserved) {
                throw ApiException.deviceVersionConflict("CAPACITY_BELOW_RESERVATION",
                        "总空间不能低于当前镜像加活跃预留: " + (request.currentImageBytes() + reserved),
                        device.spaceVersion());
            }
            deviceRepository.maintainSpace(deviceId, request.totalSpaceBytes(), request.currentImageBytes());
            return viewOf(findDevice(deviceId));
        }, DeviceView.class);
    }

    public DeviceView get(String deviceId) {
        return viewOf(findDevice(deviceId));
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }

    /**
     * 设备视图：附带当前活跃预留与余量（旧模式为 null）。
     */
    public DeviceView viewOf(Device device) {
        return DeviceView.of(device, reservedBytes(device.deviceId()));
    }

    /**
     * 当前活跃暂存预留占用（压缩包+新镜像），无预留为 0。
     */
    public long reservedBytes(String deviceId) {
        return stagingReservationRepository.findByDeviceId(deviceId)
                .map(StagingReservation::heldBytes)
                .orElse(0L);
    }

    private void validateSpaceProfile(Long totalSpaceBytes, Long currentImageBytes) {
        if ((totalSpaceBytes == null) != (currentImageBytes == null)) {
            throw ApiException.badRequest("SPACE_PROFILE_INCOMPLETE",
                    "总空间与当前镜像字节数必须同时提供或同时缺省");
        }
        if (totalSpaceBytes != null && currentImageBytes > totalSpaceBytes) {
            throw ApiException.badRequest("IMAGE_EXCEEDS_TOTAL", "当前镜像字节数不能大于总空间");
        }
    }
}
