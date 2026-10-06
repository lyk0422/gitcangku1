package com.example.starter.firmware.api;

import com.example.starter.firmware.service.BootTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 双分区试启动任务回执：试启动（TRIAL_BOOT）、健康确认（HEALTH_CONFIRM）与失败（FAILED）。
 */
@RestController
@RequestMapping("/api/boot-tasks")
public class BootTaskController {

    private final BootTaskService bootTaskService;

    public BootTaskController(BootTaskService bootTaskService) {
        this.bootTaskService = bootTaskService;
    }

    @PostMapping("/{taskId}/receipt")
    public BootTaskView receipt(@PathVariable long taskId, @Valid @RequestBody BootReceiptRequest request) {
        return bootTaskService.receipt(taskId, request);
    }
}
