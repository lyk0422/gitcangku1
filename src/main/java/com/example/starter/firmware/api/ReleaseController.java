package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.RegionThrottleService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布单：创建、扩量、取消、区域上限修改、任务明细与区域限流查询。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;
    private final TaskService taskService;
    private final RegionThrottleService regionThrottleService;

    public ReleaseController(ReleaseService releaseService, TaskService taskService,
                             RegionThrottleService regionThrottleService) {
        this.releaseService = releaseService;
        this.taskService = taskService;
        this.regionThrottleService = regionThrottleService;
    }

    @PostMapping
    public ReleaseView create(@Valid @RequestBody CreateReleaseRequest request) {
        return releaseService.create(request);
    }

    @PostMapping("/{releaseId}/expand")
    public ReleaseView expand(@PathVariable long releaseId, @Valid @RequestBody ExpandReleaseRequest request) {
        return releaseService.expand(releaseId, request);
    }

    @PostMapping("/{releaseId}/region-limit")
    public ReleaseView updateRegionLimit(@PathVariable long releaseId,
                                         @Valid @RequestBody UpdateRegionLimitRequest request) {
        return releaseService.updateRegionLimit(releaseId, request);
    }

    @PostMapping("/{releaseId}/cancel")
    public ReleaseView cancel(@PathVariable long releaseId, @Valid @RequestBody RequestIdBody request) {
        return releaseService.cancel(releaseId, request.requestId());
    }

    @GetMapping("/{releaseId}/tasks")
    public TaskListResponse tasks(@PathVariable long releaseId, @RequestParam(required = false) String status) {
        TaskStatus filter = parseStatus(status);
        return taskService.listByRelease(releaseId, filter);
    }

    @GetMapping("/{releaseId}/regions")
    public RegionOverviewResponse regions(@PathVariable long releaseId) {
        return regionThrottleService.overview(releaseId);
    }

    @GetMapping("/{releaseId}/regions/{region}/waiting")
    public WaitingListResponse waiting(@PathVariable long releaseId, @PathVariable String region) {
        return regionThrottleService.waiting(releaseId, region);
    }

    @GetMapping("/{releaseId}/regions/{region}/throttles")
    public ThrottleHistoryResponse throttles(@PathVariable long releaseId, @PathVariable String region) {
        return regionThrottleService.throttleHistory(releaseId, region);
    }

    private TaskStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_STATUS", "未知任务状态: " + status);
        }
    }
}
