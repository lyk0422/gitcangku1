package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BatchTagItemRequest;
import com.example.starter.firmware.api.BatchTagsRequest;
import com.example.starter.firmware.api.BatchTagsResponse;
import com.example.starter.firmware.api.DeviceTagsView;
import com.example.starter.firmware.api.TagDictView;
import com.example.starter.firmware.api.TagRevisionView;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.DeviceTagRepository;
import com.example.starter.firmware.repo.TagDictMetaRepository;
import com.example.starter.firmware.repo.TagRevisionRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 设备标签与标签字典：批量整批替换标签、字典版本递增、修订历史与只读诊断。
 * 批量修改先在同一事务内核对全部设备与最终标签集合，再一次性提交；任一失败整批回滚，不留半成品。
 * 与带目标策略的发布单创建共用 tag_dict_meta 行锁，按提交顺序串行裁决。
 */
@Service
public class DeviceTagService {

    private final DeviceTagRepository deviceTagRepository;
    private final DeviceRepository deviceRepository;
    private final TagDictMetaRepository dictMetaRepository;
    private final TagRevisionRepository revisionRepository;
    private final TagPolicy tagPolicy;
    private final Fingerprints fingerprints;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public DeviceTagService(DeviceTagRepository deviceTagRepository, DeviceRepository deviceRepository,
                            TagDictMetaRepository dictMetaRepository,
                            TagRevisionRepository revisionRepository, TagPolicy tagPolicy,
                            Fingerprints fingerprints, IdempotencyService idempotency, Clock clock) {
        this.deviceTagRepository = deviceTagRepository;
        this.deviceRepository = deviceRepository;
        this.dictMetaRepository = dictMetaRepository;
        this.revisionRepository = revisionRepository;
        this.tagPolicy = tagPolicy;
        this.fingerprints = fingerprints;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 批量标签修改（全量覆盖）。整批核对通过后一次事务提交；设备不存在、标签非法、同设备重复成项均 422/404。
     * 成功后字典版本加一并写一条修订历史；同 requestId 同参重放首次完整结果。
     */
    public BatchTagsResponse batchModify(BatchTagsRequest request) {
        // 事务外先做纯规范化校验：非法标签直接 422，不占幂等键
        List<NormalizedItem> normalizedItems = normalizeItems(request.items());
        ensureNoDuplicateDevice(normalizedItems);

        TreeMap<String, Object> fingerprintStructure = fingerprints.builder("device.tags.batch");
        fingerprintStructure.put("operator", request.operator());
        fingerprintStructure.put("items", normalizedItems.stream()
                .map(item -> {
                    TreeMap<String, Object> part = new TreeMap<>();
                    part.put("deviceId", item.deviceId());
                    part.put("tags", item.tags());
                    return part;
                }).toList());
        String fingerprint = fingerprints.canonical(fingerprintStructure);

        return idempotency.execute(request.requestId(), "device.tags.batch", fingerprint, () -> {
            // 锁字典版本单行：批量修改之间、与发布单快照解析之间按提交顺序串行
            dictMetaRepository.findForUpdate();
            // 整批核对：所有设备必须存在（404，可区分原因），不留半成品
            for (NormalizedItem item : normalizedItems) {
                deviceRepository.findById(item.deviceId())
                        .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                                "批量标签修改失败，设备不存在: " + item.deviceId()));
            }
            // 先核对整批最终状态：读现状计算 changed
            List<BatchTagsResponse.Item> resultItems = new ArrayList<>();
            int changedCount = 0;
            for (NormalizedItem item : normalizedItems) {
                List<String> current = deviceTagRepository.findTags(item.deviceId());
                boolean changed = !current.equals(item.tags());
                if (changed) {
                    changedCount++;
                }
                resultItems.add(new BatchTagsResponse.Item(item.deviceId(), changed, item.tags()));
            }
            // 核对全部通过后再写入
            for (NormalizedItem item : normalizedItems) {
                deviceTagRepository.replaceTags(item.deviceId(), item.tags());
            }
            long newVersion = dictMetaRepository.incrementVersion();
            Instant changedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
            revisionRepository.insert(newVersion, request.operator(), normalizedItems.size(), changedCount,
                    changedAt.toString());
            return new BatchTagsResponse(newVersion, normalizedItems.size(), changedCount,
                    normalizedItems.size() - changedCount, resultItems);
        }, BatchTagsResponse.class);
    }

    public DeviceTagsView getTags(String deviceId) {
        deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
        return new DeviceTagsView(deviceId, deviceTagRepository.findTags(deviceId));
    }

    public TagDictView dictVersion() {
        return new TagDictView(dictMetaRepository.currentVersion());
    }

    /**
     * 修订历史（只读），UTC 左闭右开区间 [from, to) 过滤；参数为 null 表示不设该侧边界。
     */
    public List<TagRevisionView> history(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("INVALID_TIME_RANGE", "时间区间必须满足 from < to（左闭右开）");
        }
        return revisionRepository.findBetween(from, to).stream().map(TagRevisionView::of).toList();
    }

    private List<NormalizedItem> normalizeItems(List<BatchTagItemRequest> items) {
        List<NormalizedItem> normalized = new ArrayList<>();
        for (BatchTagItemRequest item : items) {
            normalized.add(new NormalizedItem(item.deviceId(),
                    tagPolicy.normalizeGroup(item.tags(), "tags")));
        }
        return normalized;
    }

    private void ensureNoDuplicateDevice(List<NormalizedItem> items) {
        Set<String> seen = new HashSet<>();
        for (NormalizedItem item : items) {
            if (!seen.add(item.deviceId())) {
                throw ApiException.unprocessable("TAG_BATCH_DUPLICATE_DEVICE",
                        "同一批标签修改中设备重复出现: " + item.deviceId());
            }
        }
    }

    private record NormalizedItem(String deviceId, List<String> tags) {
    }
}
