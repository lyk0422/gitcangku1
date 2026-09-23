package com.example.starter.firmware.api;

import com.example.starter.firmware.service.RollbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多跳版本回退：计划创建、设备派发、回执、人工恢复、取消与只读明细查询。
 */
@RestController
@RequestMapping("/api/rollback-plans")
public class RollbackController {

    private final RollbackService rollbackService;

    public RollbackController(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    @PostMapping
    public RollbackPlanView create(@Valid @RequestBody CreateRollbackPlanRequest request) {
        return rollbackService.create(request);
    }

    @PostMapping("/{planId}/dispatch")
    public RollbackDispatchResponse dispatch(@PathVariable long planId,
                                             @Valid @RequestBody RollbackDispatchRequest request) {
        return rollbackService.dispatch(planId, request);
    }

    @PostMapping("/hop-tasks/{hopTaskId}/receipt")
    public RollbackHopTaskView receipt(@PathVariable long hopTaskId,
                                       @Valid @RequestBody RollbackReceiptRequest request) {
        return rollbackService.receipt(hopTaskId, request);
    }

    @PostMapping("/{planId}/resume")
    public RollbackPlanView resume(@PathVariable long planId,
                                   @Valid @RequestBody ResumeRollbackPlanRequest request) {
        return rollbackService.resume(planId, request);
    }

    @PostMapping("/{planId}/cancel")
    public RollbackPlanView cancel(@PathVariable long planId, @Valid @RequestBody RequestIdBody request) {
        return rollbackService.cancel(planId, request.requestId());
    }

    @GetMapping("/{planId}")
    public RollbackPlanDetailResponse detail(@PathVariable long planId) {
        return rollbackService.detail(planId);
    }
}
