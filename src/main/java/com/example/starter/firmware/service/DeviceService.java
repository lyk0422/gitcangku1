package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.Tags;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.DeviceTagRepository;
import com.example.starter.firmware.repo.TagDictionaryRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 设备登记与查询。型号与分桶号登记后不可修改。
 * 登记会推进标签字典版本并持字典行锁，与发布单目标快照解析按提交顺序串行。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceTagRepository deviceTagRepository;
    private final TagDictionaryRepository tagDictionaryRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public DeviceService(DeviceRepository deviceRepository, DeviceTagRepository deviceTagRepository,
                         TagDictionaryRepository tagDictionaryRepository,
                         IdempotencyService idempotency, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.deviceTagRepository = deviceTagRepository;
        this.tagDictionaryRepository = tagDictionaryRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public DeviceView register(RegisterDeviceRequest request) {
        List<String> tags = Tags.normalizeAll(request.tags());
        String fingerprint = Fingerprints.of("api", "device.register",
                "deviceId", request.deviceId(), "model", request.model(),
                "currentVersion", request.currentVersion(), "bucketNo", request.bucketNo(),
                "tags", tags);
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            tagDictionaryRepository.bump(Instant.now(clock).toString());
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), request.bucketNo()));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            for (String tag : tags) {
                deviceTagRepository.insert(request.deviceId(), tag);
            }
            return new DeviceView(request.deviceId(), request.model(), request.currentVersion(),
                    request.bucketNo(), tags);
        }, DeviceView.class);
    }

    public DeviceView get(String deviceId) {
        return DeviceView.of(findDevice(deviceId), deviceTagRepository.findByDevice(deviceId));
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
