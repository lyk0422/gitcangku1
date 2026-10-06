package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpdateDeviceSpaceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记、空间维护与查询。型号与分桶号登记后不可修改。
 * 空间档案（版本1）：登记时版本从1开始，维护与首次任务终结加一；
 * 维护调小容量低于当前镜像加活跃暂存预留时 409 并携带当前档案版本。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final TaskRepository taskRepository;
    private final IdempotencyService idempotency;

    public DeviceService(DeviceRepository deviceRepository, TaskRepository taskRepository,
                         IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.taskRepository = taskRepository;
        this.idempotency = idempotency;
    }

    public DeviceView register(RegisterDeviceRequest request) {
        validateSpaceProfile(request.totalSpaceBytes(), request.currentImageBytes());
        Integer spaceVersion = request.totalSpaceBytes() == null ? null : 1;
        String fingerprint = Fingerprints.of("device.register", request.deviceId(), request.model(),
                request.currentVersion(), request.bucketNo(),
                request.totalSpaceBytes(), request.currentImageBytes());
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            Device device = new Device(request.deviceId(), request.model(), request.currentVersion(),
                    request.bucketNo(), request.totalSpaceBytes(), request.currentImageBytes(), spaceVersion);
            try {
                deviceRepository.insert(device);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return DeviceView.of(device, 0);
        }, DeviceView.class);
    }

    /**
     * 空间维护：设备行锁内校验档案版本与占用下限，成功则总空间更新且档案版本加一。
     */
    public DeviceView updateSpace(String deviceId, UpdateDeviceSpaceRequest request) {
        String fingerprint = Fingerprints.of("device.space.update", deviceId,
                request.totalSpaceBytes(), request.expectedDeviceVersion());
        return idempotency.execute(request.requestId(), "device.space.update", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (!device.spaceTracked()) {
                throw ApiException.conflict("DEVICE_SPACE_UNTRACKED",
                        "设备未登记空间档案，保留旧模式: " + deviceId);
            }
            if (device.spaceVersion() != request.expectedDeviceVersion()) {
                throw ApiException.deviceVersionConflict("DEVICE_VERSION_CONFLICT",
                        "expectedDeviceVersion 与当前空间档案版本不一致: " + device.spaceVersion(),
                        device.spaceVersion());
            }
            long activeReserved = taskRepository.sumActiveReservedBytes(deviceId);
            long inUse = device.currentImageBytes() + activeReserved;
            if (request.totalSpaceBytes() < inUse) {
                throw ApiException.deviceVersionConflict("SPACE_BELOW_IN_USE",
                        "总空间不能低于当前镜像加活跃暂存预留: " + inUse, device.spaceVersion());
            }
            deviceRepository.updateSpace(deviceId, request.totalSpaceBytes());
            return DeviceView.of(findDevice(deviceId), activeReserved);
        }, DeviceView.class);
    }

    public DeviceView get(String deviceId) {
        Device device = findDevice(deviceId);
        return DeviceView.of(device, taskRepository.sumActiveReservedBytes(deviceId));
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }

    /**
     * 空间档案字段必须成对提供，且当前镜像字节数不超过总空间。
     */
    private void validateSpaceProfile(Long totalSpaceBytes, Long currentImageBytes) {
        if ((totalSpaceBytes == null) != (currentImageBytes == null)) {
            throw ApiException.badRequest("SPACE_FIELDS_INCOMPLETE",
                    "totalSpaceBytes 与 currentImageBytes 必须同时提供或同时省略");
        }
        if (totalSpaceBytes != null && currentImageBytes > totalSpaceBytes) {
            throw ApiException.badRequest("IMAGE_EXCEEDS_TOTAL",
                    "当前镜像字节数不能超过总空间: " + currentImageBytes + " > " + totalSpaceBytes);
        }
    }
}
