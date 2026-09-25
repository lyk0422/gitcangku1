package com.example.starter.firmware.api;

import com.example.starter.firmware.service.RegionThrottleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 区域带宽限流查询：进行中任务数、等待清单与限流历史。
 */
@RestController
@RequestMapping("/api/releases/{releaseId}/regions/{region}")
public class RegionThrottleController {

    private final RegionThrottleService regionThrottleService;

    public RegionThrottleController(RegionThrottleService regionThrottleService) {
        this.regionThrottleService = regionThrottleService;
    }

    @GetMapping("/in-flight")
    public RegionInFlightView inFlight(@PathVariable long releaseId, @PathVariable String region) {
        return regionThrottleService.inFlight(releaseId, region);
    }

    @GetMapping("/waiting")
    public WaitingListResponse waiting(@PathVariable long releaseId, @PathVariable String region) {
        return regionThrottleService.waiting(releaseId, region);
    }

    @GetMapping("/throttle-events")
    public ThrottleEventListResponse throttleEvents(@PathVariable long releaseId, @PathVariable String region) {
        return regionThrottleService.throttleEvents(releaseId, region);
    }
}
