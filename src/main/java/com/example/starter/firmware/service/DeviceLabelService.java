package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BatchLabelUpdateRequest;
import com.example.starter.firmware.api.BatchLabelUpdateResponse;
import com.example.starter.firmware.api.DeviceLabelsItem;
import com.example.starter.firmware.api.DeviceLabelsView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.LabelExpression;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 批量标签修改。语义：
 * <ul>
 *   <li>先在事务外核对整批最终状态：设备存在、设备不重复、标签合法（空白 422）；任一失败整体拒绝；</li>
 *   <li>核对通过后在单事务内提交：字典 meta 行锁 → 逐设备行锁（deviceId 字典序）→ 整体替换标签，
 *       合并字典新标签、至多推进一次版本、写一条批量修订事件与幂等记录，任一失败全部回滚不留半成品；</li>
 *   <li>已下发任务（rollout_task）与发布单目标快照不随标签修改改变。</li>
 * </ul>
 */
@Service
public class DeviceLabelService {

    private final DeviceRepository deviceRepository;
    private final LabelDictService labelDictService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DeviceLabelService(DeviceRepository deviceRepository, LabelDictService labelDictService,
                              IdempotencyService idempotency, ObjectMapper objectMapper, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.labelDictService = labelDictService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public BatchLabelUpdateResponse updateBatch(BatchLabelUpdateRequest request) {
        // 预检：规范化全部标签集合，按 deviceId 去重排序；任何一台失败都拒绝整批（此时尚未开事务、不占键）
        Map<String, List<String>> normalizedByDevice = new LinkedHashMap<>();
        for (DeviceLabelsItem item : request.items()) {
            List<String> labels = LabelExpression.normalizeTags(item.labels());
            if (normalizedByDevice.put(item.deviceId(), labels) != null) {
                throw ApiException.unprocessable("BATCH_DEVICE_DUPLICATED",
                        "批量标签修改存在重复设备: " + item.deviceId());
            }
        }
        List<String> orderedDeviceIds = new ArrayList<>(new TreeSet<>(normalizedByDevice.keySet()));
        for (String deviceId : orderedDeviceIds) {
            if (deviceRepository.findById(deviceId).isEmpty()) {
                throw ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId);
            }
        }

        List<Map<String, Object>> fingerprintItems = orderedDeviceIds.stream()
                .map(id -> (Map<String, Object>) Fingerprint.payload("deviceId", id,
                        "labels", normalizedByDevice.get(id)))
                .toList();
        String fingerprint = Fingerprint.of(objectMapper, "device.labelBatchUpdate", request.operator(),
                Fingerprint.payload("devices", fingerprintItems));

        return idempotency.execute(request.requestId(), "device.labelBatchUpdate", fingerprint, () -> {
            // 字典 meta 行锁先于设备行锁获取，统一全局加锁顺序
            List<String> allTags = new TreeSet<>(normalizedByDevice.values().stream()
                    .flatMap(List::stream).toList()).stream().toList();
            LabelDictService.MergeOutcome outcome = labelDictService.mergeTagsLocked(allTags);

            List<DeviceLabelsView> deviceViews = new ArrayList<>();
            for (String deviceId : orderedDeviceIds) {
                List<String> labels = normalizedByDevice.get(deviceId);
                // 行锁逐台读取后整体替换，锁覆盖读取与写入
                Device locked = deviceRepository.findByIdForUpdate(deviceId)
                        .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
                deviceRepository.updateLabels(locked.deviceId(), labels);
                deviceViews.add(new DeviceLabelsView(locked.deviceId(), labels));
            }
            labelDictService.recordBatchUpdate(request.operator(), orderedDeviceIds.size(), outcome);
            // 版本只在实际引入新标签时推进一次，故差额恰为 0 或 1
            long versionDelta = outcome.newTagCount() > 0 ? 1 : 0;
            return new BatchLabelUpdateResponse(request.requestId(), request.operator(),
                    orderedDeviceIds.size(), orderedDeviceIds.size(), outcome.newTagCount(),
                    outcome.versionAfter(), versionDelta,
                    Instant.now(clock).toString(), deviceViews);
        }, BatchLabelUpdateResponse.class);
    }
}
