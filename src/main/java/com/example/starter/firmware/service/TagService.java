package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceTagBatchResponse;
import com.example.starter.firmware.api.TagBatchUpdateRequest;
import com.example.starter.firmware.api.TagChangeRecordView;
import com.example.starter.firmware.api.TagHistoryResponse;
import com.example.starter.firmware.domain.TagChangeRecord;
import com.example.starter.firmware.domain.Tags;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.DeviceTagRepository;
import com.example.starter.firmware.repo.TagChangeRepository;
import com.example.starter.firmware.repo.TagDictionaryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 设备标签批量修改与变更历史查询。
 * 批量修改：先规范化并校验全部设备的完整最终状态，再在单个事务中提交；
 * 字典行锁使并发批量修改、设备登记与发布单目标快照解析按提交顺序串行。
 */
@Service
public class TagService {

    private static final TypeReference<List<TagChangeRecordView.ChangeItem>> CHANGE_LIST =
            new TypeReference<>() {
            };

    private final DeviceRepository deviceRepository;
    private final DeviceTagRepository deviceTagRepository;
    private final TagDictionaryRepository tagDictionaryRepository;
    private final TagChangeRepository tagChangeRepository;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TagService(DeviceRepository deviceRepository, DeviceTagRepository deviceTagRepository,
                      TagDictionaryRepository tagDictionaryRepository,
                      TagChangeRepository tagChangeRepository, IdempotencyService idempotency,
                      ObjectMapper objectMapper, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.deviceTagRepository = deviceTagRepository;
        this.tagDictionaryRepository = tagDictionaryRepository;
        this.tagChangeRepository = tagChangeRepository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 规范化后的单设备变更：add/remove 为规范化升序标签。
     */
    private record NormalizedUpdate(String deviceId, List<String> add, List<String> remove) {
    }

    /**
     * 校验后的待执行变更：toInsert/toDelete 均为相对当前状态的实际增删。
     */
    private record PlannedUpdate(NormalizedUpdate update, List<String> toInsert,
                                 List<String> toDelete) {
    }

    public DeviceTagBatchResponse batchUpdate(TagBatchUpdateRequest request) {
        List<NormalizedUpdate> updates = normalizeUpdates(request.updates());
        List<Map<String, Object>> fingerprintUpdates = updates.stream()
                .map(u -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("deviceId", u.deviceId());
                    item.put("add", u.add());
                    item.put("remove", u.remove());
                    return item;
                })
                .toList();
        String fingerprint = Fingerprints.of("api", "device.tags.batchUpdate",
                "operator", request.operator(), "updates", fingerprintUpdates);
        return idempotency.execute(request.requestId(), "device.tags.batchUpdate", fingerprint, () -> {
            String changedAtUtc = Instant.now(clock).toString();
            long dictVersion = tagDictionaryRepository.bump(changedAtUtc);
            // 先校验全部设备的完整最终状态，再统一落库
            List<PlannedUpdate> planned = new ArrayList<>();
            for (NormalizedUpdate update : updates) {
                if (deviceRepository.findById(update.deviceId()).isEmpty()) {
                    throw ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + update.deviceId());
                }
                Set<String> current = new TreeSet<>(
                        deviceTagRepository.findByDevice(update.deviceId()));
                List<String> toInsert = update.add().stream()
                        .filter(tag -> !current.contains(tag)).toList();
                List<String> toDelete = update.remove().stream()
                        .filter(current::contains).toList();
                planned.add(new PlannedUpdate(update, toInsert, toDelete));
            }
            for (PlannedUpdate plan : planned) {
                for (String tag : plan.toDelete()) {
                    deviceTagRepository.delete(plan.update().deviceId(), tag);
                }
                for (String tag : plan.toInsert()) {
                    deviceTagRepository.insert(plan.update().deviceId(), tag);
                }
            }
            List<TagChangeRecordView.ChangeItem> changes = planned.stream()
                    .map(plan -> new TagChangeRecordView.ChangeItem(plan.update().deviceId(),
                            plan.update().add(), plan.update().remove()))
                    .toList();
            tagChangeRepository.insert(dictVersion, request.operator(), planned.size(),
                    writeJson(changes), changedAtUtc);
            return new DeviceTagBatchResponse(dictVersion, planned.size(), changedAtUtc);
        }, DeviceTagBatchResponse.class);
    }

    /**
     * 变更历史查询（只读）。from/to 为 UTC ISO-8601 时刻，区间左闭右开 [from, to)；
     * 缺省不限制。结果按变更记录ID升序（提交顺序）。
     */
    public TagHistoryResponse history(String from, String to) {
        Instant fromInstant = parseInstant("from", from);
        Instant toInstant = parseInstant("to", to);
        if (fromInstant != null && toInstant != null && !fromInstant.isBefore(toInstant)) {
            throw ApiException.badRequest("INVALID_RANGE", "from 必须早于 to（区间左闭右开）");
        }
        List<TagChangeRecordView> records = tagChangeRepository.findAll().stream()
                .filter(record -> inRange(record, fromInstant, toInstant))
                .map(this::toView)
                .toList();
        return new TagHistoryResponse(records);
    }

    private boolean inRange(TagChangeRecord record, Instant from, Instant to) {
        Instant changedAt = Instant.parse(record.changedAtUtc());
        return (from == null || !changedAt.isBefore(from))
                && (to == null || changedAt.isBefore(to));
    }

    private TagChangeRecordView toView(TagChangeRecord record) {
        try {
            List<TagChangeRecordView.ChangeItem> changes =
                    objectMapper.readValue(record.changesJson(), CHANGE_LIST);
            return new TagChangeRecordView(record.id(), record.dictVersion(), record.operator(),
                    record.deviceCount(), changes, record.changedAtUtc());
        } catch (Exception e) {
            throw new IllegalStateException("标签变更历史反序列化失败", e);
        }
    }

    private Instant parseInstant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_TIME",
                    name + " 不是合法的 UTC ISO-8601 时刻: " + value);
        }
    }

    /**
     * 规范化批量变更：标签去空白转小写、集合升序、设备按 deviceId 升序；
     * 重复设备、空变更、add 与 remove 交集均返回 422。
     */
    private List<NormalizedUpdate> normalizeUpdates(List<TagBatchUpdateRequest.TagUpdateItem> items) {
        Set<String> seenDevices = new HashSet<>();
        List<NormalizedUpdate> normalized = new ArrayList<>();
        for (TagBatchUpdateRequest.TagUpdateItem item : items) {
            if (!seenDevices.add(item.deviceId())) {
                throw ApiException.unprocessable("DUPLICATE_DEVICE",
                        "同一批次中设备重复: " + item.deviceId());
            }
            List<String> add = Tags.normalizeAll(item.add());
            List<String> remove = Tags.normalizeAll(item.remove());
            if (add.isEmpty() && remove.isEmpty()) {
                throw ApiException.unprocessable("EMPTY_TAG_CHANGE",
                        "设备 " + item.deviceId() + " 的 add 与 remove 不能同时为空");
            }
            for (String tag : add) {
                if (remove.contains(tag)) {
                    throw ApiException.unprocessable("TAG_ADD_REMOVE_CONFLICT",
                            "标签同时出现在 add 与 remove: " + tag);
                }
            }
            normalized.add(new NormalizedUpdate(item.deviceId(), add, remove));
        }
        normalized.sort(Comparator.comparing(NormalizedUpdate::deviceId));
        return normalized;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("标签变更快照序列化失败", e);
        }
    }
}
