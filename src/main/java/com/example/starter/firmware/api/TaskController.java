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
 * 投放任务：回执、人工复核确认与任务详情查询（含固化证书链版本）。
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

    @PostMapping("/{taskId}/confirmReview")
    public TaskView confirmReview(@PathVariable long taskId, @Valid @RequestBody ConfirmReviewRequest request) {
        return taskService.confirmReview(taskId, request);
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable long taskId) {
        return taskService.getTask(taskId);
    }
}
