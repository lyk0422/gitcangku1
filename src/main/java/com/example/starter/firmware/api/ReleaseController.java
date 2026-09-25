package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
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
 * 发布单：创建、扩量、取消、人工恢复、监控统计与暂停/恢复历史、任务明细查询。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;
    private final TaskService taskService;

    public ReleaseController(ReleaseService releaseService, TaskService taskService) {
        this.releaseService = releaseService;
        this.taskService = taskService;
    }

    @PostMapping
    public ReleaseView create(@Valid @RequestBody CreateReleaseRequest request) {
        return releaseService.create(request);
    }

    @GetMapping("/precheck")
    public PrecheckView precheck(@RequestParam String model, @RequestParam String fromVersion,
                                 @RequestParam int ratio) {
        if (ratio < 0 || ratio > 100) {
            throw ApiException.badRequest("INVALID_RATIO", "投放比例取值0~100: " + ratio);
        }
        return releaseService.precheck(model, fromVersion, ratio);
    }

    @PostMapping("/{releaseId}/expand")
    public ReleaseView expand(@PathVariable long releaseId, @Valid @RequestBody ExpandReleaseRequest request) {
        return releaseService.expand(releaseId, request);
    }

    @PostMapping("/{releaseId}/cancel")
    public ReleaseView cancel(@PathVariable long releaseId, @Valid @RequestBody RequestIdBody request) {
        return releaseService.cancel(releaseId, request.requestId());
    }

    @PostMapping("/{releaseId}/resume")
    public ReleaseView resume(@PathVariable long releaseId, @Valid @RequestBody ResumeReleaseRequest request) {
        return releaseService.resume(releaseId, request);
    }

    @GetMapping("/{releaseId}/monitor")
    public MonitorView monitor(@PathVariable long releaseId) {
        return releaseService.monitor(releaseId);
    }

    @GetMapping("/{releaseId}/history")
    public ReleaseHistoryResponse history(@PathVariable long releaseId) {
        return releaseService.history(releaseId);
    }

    @GetMapping("/{releaseId}/tasks")
    public TaskListResponse tasks(@PathVariable long releaseId, @RequestParam(required = false) String status) {
        TaskStatus filter = parseStatus(status);
        return taskService.listByRelease(releaseId, filter);
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
