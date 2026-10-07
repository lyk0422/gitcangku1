package com.example.starter.firmware.api;

import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放任务执行租约（领取、续租）与回执。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/{taskId}/lease")
    public LeaseView acquire(@PathVariable long taskId, @Valid @RequestBody LeaseRequest request) {
        return taskService.acquire(taskId, request);
    }

    @PostMapping("/{taskId}/lease/renew")
    public LeaseView renew(@PathVariable long taskId, @Valid @RequestBody RenewLeaseRequest request) {
        return taskService.renew(taskId, request);
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        boolean agentPresent = request.agentId() != null && !request.agentId().isBlank();
        boolean tokenPresent = request.token() != null;
        if (agentPresent != tokenPresent) {
            throw ApiException.badRequest("LEASE_CREDENTIALS_INCOMPLETE",
                    "租约模式回执必须同时携带非空 agentId 与正数 token");
        }
        return taskService.receipt(taskId, request);
    }
}
