package com.example.starter.firmware.api;

import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放任务：回执、任务详情（含固化证书链版本与复核状态）、人工确认。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable long taskId) {
        return taskService.get(taskId);
    }

    @PostMapping("/{taskId}/confirm")
    public TaskView confirm(@PathVariable long taskId, @Valid @RequestBody ConfirmTaskRequest request) {
        return taskService.confirm(taskId, request);
    }
}
