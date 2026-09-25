package com.example.starter.firmware.api;

import com.example.starter.firmware.service.QuarantineService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放任务开始、回执与取消原因查询。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final QuarantineService quarantineService;

    public TaskController(TaskService taskService, QuarantineService quarantineService) {
        this.taskService = taskService;
        this.quarantineService = quarantineService;
    }

    @PostMapping("/{taskId}/start")
    public TaskView start(@PathVariable long taskId, @Valid @RequestBody RequestIdBody request) {
        return taskService.start(taskId, request.requestId());
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    @GetMapping("/{taskId}/cancel-reason")
    public TaskCancelReasonView cancelReason(@PathVariable long taskId) {
        return quarantineService.cancelReasonOfTask(taskId);
    }
}
