package com.example.starter.firmware.api;

import com.example.starter.firmware.service.IntegrityService;
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
 * 投放任务：回执、分片接收与完整性诊断查询。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final IntegrityService integrityService;

    public TaskController(TaskService taskService, IntegrityService integrityService) {
        this.taskService = taskService;
        this.integrityService = integrityService;
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    @PostMapping("/{taskId}/chunks")
    public ChunkSubmissionView submitChunks(@PathVariable long taskId,
                                            @Valid @RequestBody SubmitChunksRequest request) {
        return integrityService.submitChunks(taskId, request);
    }

    @GetMapping("/{taskId}/integrity")
    public TaskIntegrityView integrity(@PathVariable long taskId,
                                       @RequestParam(required = false) String fromUtc,
                                       @RequestParam(required = false) String toUtc) {
        return integrityService.taskIntegrity(taskId, fromUtc, toUtc);
    }
}
