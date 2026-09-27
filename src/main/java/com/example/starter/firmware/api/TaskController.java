package com.example.starter.firmware.api;

import com.example.starter.firmware.service.ReviewService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放任务：批量启动、回执与撤销链回执的人工确认/拒绝、复核查询。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final ReviewService reviewService;

    public TaskController(TaskService taskService, ReviewService reviewService) {
        this.taskService = taskService;
        this.reviewService = reviewService;
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    @GetMapping("/{taskId}/review")
    public ReviewView getReview(@PathVariable long taskId) {
        return reviewService.get(taskId);
    }

    @PostMapping("/{taskId}/review/confirm")
    public ReviewView confirm(@PathVariable long taskId, @Valid @RequestBody ConfirmReviewRequest request) {
        return reviewService.confirm(taskId, request);
    }

    @PostMapping("/{taskId}/review/reject")
    public ReviewView reject(@PathVariable long taskId, @Valid @RequestBody RejectReviewRequest request) {
        return reviewService.reject(taskId, request);
    }
}
