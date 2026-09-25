package com.example.starter.firmware.service;

import com.example.starter.firmware.api.RegionInFlightView;
import com.example.starter.firmware.api.RegionLimitConfigView;
import com.example.starter.firmware.api.RegionLimitItem;
import com.example.starter.firmware.api.ThrottleEventListResponse;
import com.example.starter.firmware.api.ThrottleEventView;
import com.example.starter.firmware.api.UpdateRegionLimitsRequest;
import com.example.starter.firmware.api.WaitingDeviceView;
import com.example.starter.firmware.api.WaitingListResponse;
import com.example.starter.firmware.domain.RegionLimit;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.RegionLimitRepository;
import com.example.starter.firmware.repo.RegionThrottleEventRepository;
import com.example.starter.firmware.repo.RegionWaitRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 区域带宽限流：发布单区域上限配置修改与限流查询。
 * 配置修改与拉取、回执共用发布单行锁（SELECT ... FOR UPDATE），按事务提交顺序裁决。
 */
@Service
public class RegionThrottleService {

    private final ReleaseRepository releaseRepository;
    private final RegionLimitRepository regionLimitRepository;
    private final RegionWaitRepository regionWaitRepository;
    private final RegionThrottleEventRepository throttleEventRepository;
    private final TaskRepository taskRepository;
    private final IdempotencyService idempotency;

    public RegionThrottleService(ReleaseRepository releaseRepository,
                                 RegionLimitRepository regionLimitRepository,
                                 RegionWaitRepository regionWaitRepository,
                                 RegionThrottleEventRepository throttleEventRepository,
                                 TaskRepository taskRepository,
                                 IdempotencyService idempotency) {
        this.releaseRepository = releaseRepository;
        this.regionLimitRepository = regionLimitRepository;
        this.regionWaitRepository = regionWaitRepository;
        this.throttleEventRepository = throttleEventRepository;
        this.taskRepository = taskRepository;
        this.idempotency = idempotency;
    }

    /**
     * 全量替换发布单区域上限：expectedVersion 冲突 409；成功版本加一；只影响后续拉取判定。
     */
    public RegionLimitConfigView updateLimits(long releaseId, UpdateRegionLimitsRequest request) {
        List<RegionLimitItem> items = request.limits();
        Set<String> seen = new HashSet<>();
        for (RegionLimitItem item : items) {
            if (!seen.add(item.region())) {
                throw ApiException.badRequest("DUPLICATE_REGION", "区域上限配置重复: " + item.region());
            }
        }
        List<RegionLimitItem> sorted = items.stream()
                .sorted(Comparator.comparing(RegionLimitItem::region))
                .toList();
        StringBuilder fingerprint = new StringBuilder("release.regionLimits|").append(releaseId)
                .append('|').append(request.expectedVersion());
        for (RegionLimitItem item : sorted) {
            fingerprint.append('|').append(item.region()).append(':').append(item.maxInFlight());
        }
        return idempotency.execute(request.requestId(), "release.regionLimits", fingerprint.toString(), () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE", "发布单已取消，不能修改区域上限");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            regionLimitRepository.replaceAll(releaseId, sorted.stream()
                    .map(item -> new RegionLimit(releaseId, item.region(), item.maxInFlight()))
                    .toList());
            releaseRepository.bumpVersion(releaseId, request.expectedVersion());
            return new RegionLimitConfigView(releaseId, request.expectedVersion() + 1, sorted);
        }, RegionLimitConfigView.class);
    }

    /**
     * 区域当前进行中（已下发未完成）任务数与配置上限；上限未配置时 maxInFlight 为 null。
     */
    public RegionInFlightView inFlight(long releaseId, String region) {
        requireOrder(releaseId);
        long inFlight = taskRepository.countInFlightByRegion(releaseId, region);
        Integer maxInFlight = regionLimitRepository.findLimit(releaseId, region).orElse(null);
        return new RegionInFlightView(releaseId, region, inFlight, maxInFlight);
    }

    /**
     * 区域等待清单：按等待时刻升序、同刻按设备ID字典序。
     */
    public WaitingListResponse waiting(long releaseId, String region) {
        requireOrder(releaseId);
        List<WaitingDeviceView> devices = regionWaitRepository.findByRegion(releaseId, region).stream()
                .map(record -> new WaitingDeviceView(record.deviceId(), record.waitedAt()))
                .toList();
        return new WaitingListResponse(devices);
    }

    /**
     * 区域限流历史：按发生顺序升序。
     */
    public ThrottleEventListResponse throttleEvents(long releaseId, String region) {
        requireOrder(releaseId);
        List<ThrottleEventView> events = throttleEventRepository.findByRegion(releaseId, region).stream()
                .map(event -> new ThrottleEventView(event.deviceId(), event.throttledAt()))
                .toList();
        return new ThrottleEventListResponse(events);
    }

    private ReleaseOrder requireOrder(long releaseId) {
        return releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
    }
}
