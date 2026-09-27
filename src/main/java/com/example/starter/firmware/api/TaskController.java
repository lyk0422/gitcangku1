package com.example.starter.firmware.api;

import com.example.starter.firmware.service.IntegrityService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放任务：安装回执、分片接收、重新拉取与完整性诊断。
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
    public IntegrityView receiveChunks(@PathVariable long taskId,
                                       @Valid @RequestBody ChunkBatchRequest request) {
        return integrityService.receiveChunks(taskId, request);
    }

    @PostMapping("/{taskId}/repull")
    public IntegrityView repull(@PathVariable long taskId, @Valid @RequestBody RequestIdBody request) {
        return integrityService.repull(taskId, request.requestId());
    }

    @GetMapping("/{taskId}/integrity")
    public IntegrityView integrity(@PathVariable long taskId) {
        return integrityService.diagnose(taskId);
    }

    @GetMapping("/{taskId}/attempts")
    public AttemptHistoryResponse attempts(@PathVariable long taskId) {
        return integrityService.history(taskId);
    }
}
