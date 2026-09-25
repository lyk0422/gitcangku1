package com.example.starter.firmware.service;

import com.example.starter.firmware.api.RegionOverviewResponse;
import com.example.starter.firmware.api.ThrottleHistoryResponse;
import com.example.starter.firmware.api.WaitingListResponse;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.repo.RegionThrottleRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 区域限流查询：区域当前进行中任务数、等待清单与限流历史。
 */
@Service
public class RegionThrottleService {

    private final RegionThrottleRepository regionThrottleRepository;
    private final ReleaseService releaseService;

    public RegionThrottleService(RegionThrottleRepository regionThrottleRepository,
                                 ReleaseService releaseService) {
        this.regionThrottleRepository = regionThrottleRepository;
        this.releaseService = releaseService;
    }

    public RegionOverviewResponse overview(long releaseId) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<RegionOverviewResponse.RegionOverview> regions = regionThrottleRepository.listRegions(releaseId)
                .stream()
                .map(region -> new RegionOverviewResponse.RegionOverview(region,
                        regionThrottleRepository.countInFlight(releaseId, region),
                        regionThrottleRepository.findWaiting(releaseId, region).size()))
                .toList();
        return new RegionOverviewResponse(order.id(), order.regionLimit(), regions);
    }

    public WaitingListResponse waiting(long releaseId, String region) {
        releaseService.findOrder(releaseId);
        List<WaitingListResponse.WaitingEntry> waiting = regionThrottleRepository.findWaiting(releaseId, region)
                .stream()
                .map(entry -> new WaitingListResponse.WaitingEntry(entry.deviceId(), entry.lastThrottledAt()))
                .toList();
        return new WaitingListResponse(waiting);
    }

    public ThrottleHistoryResponse throttleHistory(long releaseId, String region) {
        releaseService.findOrder(releaseId);
        List<ThrottleHistoryResponse.ThrottleEventView> throttles =
                regionThrottleRepository.listThrottleEvents(releaseId, region)
                        .stream()
                        .map(event -> new ThrottleHistoryResponse.ThrottleEventView(event.deviceId(),
                                event.throttledAt()))
                        .toList();
        return new ThrottleHistoryResponse(throttles);
    }
}
