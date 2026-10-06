package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DualReleaseService;
import com.example.starter.firmware.service.DualTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 双分区试启动发布单：创建、暂停、查询与任务明细。
 */
@RestController
@RequestMapping("/api/dual-releases")
public class DualReleaseController {

    private final DualReleaseService dualReleaseService;
    private final DualTaskService dualTaskService;

    public DualReleaseController(DualReleaseService dualReleaseService, DualTaskService dualTaskService) {
        this.dualReleaseService = dualReleaseService;
        this.dualTaskService = dualTaskService;
    }

    @PostMapping
    public DualReleaseView create(@Valid @RequestBody CreateDualReleaseRequest request) {
        return dualReleaseService.create(request);
    }

    @PostMapping("/{releaseId}/pause")
    public DualReleaseView pause(@PathVariable long releaseId,
                                 @Valid @RequestBody PauseDualReleaseRequest request) {
        return dualReleaseService.pause(releaseId, request);
    }

    @GetMapping("/{releaseId}")
    public DualReleaseView get(@PathVariable long releaseId) {
        return dualReleaseService.get(releaseId);
    }

    @GetMapping("/{releaseId}/tasks")
    public DualTaskListResponse tasks(@PathVariable long releaseId) {
        return dualTaskService.listByRelease(releaseId);
    }
}
