package com.example.starter.firmware.api;

import com.example.starter.firmware.service.DualTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 双分区试启动任务：试启动回执、健康确认、失败回执与单任务查询。
 */
@RestController
@RequestMapping("/api/dual-tasks")
public class DualTaskController {

    private final DualTaskService dualTaskService;

    public DualTaskController(DualTaskService dualTaskService) {
        this.dualTaskService = dualTaskService;
    }

    @PostMapping("/{taskId}/trial")
    public DualTaskView trial(@PathVariable long taskId, @Valid @RequestBody DualTrialRequest request) {
        return dualTaskService.trial(taskId, request);
    }

    @PostMapping("/{taskId}/confirm")
    public DualTaskView confirm(@PathVariable long taskId, @Valid @RequestBody DualReceiptRequest request) {
        return dualTaskService.confirm(taskId, request);
    }

    @PostMapping("/{taskId}/fail")
    public DualTaskView fail(@PathVariable long taskId, @Valid @RequestBody DualReceiptRequest request) {
        return dualTaskService.fail(taskId, request);
    }

    @GetMapping("/{taskId}")
    public DualTaskView get(@PathVariable long taskId) {
        return dualTaskService.getTask(taskId);
    }
}
