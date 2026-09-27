package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.CompatibilityService;
import com.example.starter.firmware.service.ExemptionService;
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
 * 发布单：创建、扩量、取消、人工恢复、监控统计与暂停/恢复历史、任务明细查询、
 * 模块豁免修改/查询与兼容判定历史诊断。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;
    private final TaskService taskService;
    private final ExemptionService exemptionService;
    private final CompatibilityService compatibilityService;

    public ReleaseController(ReleaseService releaseService, TaskService taskService,
                             ExemptionService exemptionService,
                             CompatibilityService compatibilityService) {
        this.releaseService = releaseService;
        this.taskService = taskService;
        this.exemptionService = exemptionService;
        this.compatibilityService = compatibilityService;
    }

    @PostMapping
    public ReleaseView create(@Valid @RequestBody CreateReleaseRequest request) {
        return releaseService.create(request);
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

    /**
     * 整体修改发布单模块豁免（整体替换，产生新豁免版本）。
     */
    @PostMapping("/{releaseId}/exemptions")
    public ExemptionView modifyExemptions(@PathVariable long releaseId,
                                          @Valid @RequestBody ModifyExemptionsRequest request) {
        return exemptionService.modify(releaseId, request);
    }

    /**
     * 查询豁免；version 缺省查最新版本，版本0返回空集合。只读。
     */
    @GetMapping("/{releaseId}/exemptions")
    public ExemptionView exemptions(@PathVariable long releaseId,
                                    @RequestParam(required = false) Integer version) {
        return exemptionService.get(releaseId, version);
    }

    /**
     * 发布单维度兼容判定历史诊断（只读，不修改状态），按提交顺序返回。
     */
    @GetMapping("/{releaseId}/checks")
    public CheckHistoryResponse checks(@PathVariable long releaseId) {
        releaseService.findOrder(releaseId);
        return new CheckHistoryResponse(compatibilityService.listByRelease(releaseId).stream()
                .map(CheckView::of).toList());
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
