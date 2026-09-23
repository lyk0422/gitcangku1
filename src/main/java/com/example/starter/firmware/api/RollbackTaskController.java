package com.example.starter.firmware.api;

import com.example.starter.firmware.service.RollbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回退波次任务回执。
 */
@RestController
@RequestMapping("/api/rollback-tasks")
public class RollbackTaskController {

    private final RollbackService rollbackService;

    public RollbackTaskController(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    @PostMapping("/{taskId}/receipt")
    public RollbackTaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRollbackRequest request) {
        return rollbackService.receipt(taskId, request);
    }
}
