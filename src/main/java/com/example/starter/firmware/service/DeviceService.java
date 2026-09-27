package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.LabelExpression;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备登记与查询。型号与分桶号登记后不可修改；标签登记时规范化（trim、去重、字典序排序），
 * 登记引入的新标签推进标签字典版本。标签后续变更走批量标签修改，不追溯既有发布单快照。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;
    private final LabelDictService labelDictService;
    private final ObjectMapper objectMapper;

    public DeviceService(DeviceRepository deviceRepository, IdempotencyService idempotency,
                         LabelDictService labelDictService, ObjectMapper objectMapper) {
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
        this.labelDictService = labelDictService;
        this.objectMapper = objectMapper;
    }

    public DeviceView register(RegisterDeviceRequest request) {
        // 标签先规范化：非法标签 422，失败发生在幂等占位之前，不占键
        List<String> labels = LabelExpression.normalizeTags(request.labels());
        String fingerprint = Fingerprint.of(objectMapper, "device.register", request.operator(),
                Fingerprint.payload("deviceId", request.deviceId(), "model", request.model(),
                        "currentVersion", request.currentVersion(), "bucketNo", request.bucketNo(),
                        "labels", labels));
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            // 字典 meta 行锁 → 设备行锁，与批量标签修改、发布快照解析保持一致加锁顺序
            LabelDictService.MergeOutcome outcome = labelDictService.mergeTagsLocked(labels);
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), request.bucketNo(), labels));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            labelDictService.recordDeviceRegistrationIfChanged(request.operator(), outcome);
            return new DeviceView(request.deviceId(), request.model(), request.currentVersion(),
                    request.bucketNo(), labels);
        }, DeviceView.class);
    }

    public DeviceView get(String deviceId) {
        return DeviceView.of(findDevice(deviceId));
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
