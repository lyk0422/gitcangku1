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
 * 投放任务：分片接收（单片/批量）、重新拉取、撤销、安装回执与诊断查询。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/{taskId}/shards")
    public TaskView receiveShard(@PathVariable long taskId, @Valid @RequestBody ShardReceiptRequest request) {
        return taskService.receiveShard(taskId, request);
    }

    @PostMapping("/{taskId}/shards/batch")
    public TaskView receiveShardsBatch(@PathVariable long taskId,
                                       @Valid @RequestBody BatchShardReceiptRequest request) {
        return taskService.receiveShardsBatch(taskId, request);
    }

    @PostMapping("/{taskId}/repull")
    public TaskView repull(@PathVariable long taskId, @Valid @RequestBody RequestIdBody request) {
        return taskService.repull(taskId, request.requestId());
    }

    @PostMapping("/{taskId}/cancel")
    public TaskView cancel(@PathVariable long taskId, @Valid @RequestBody RequestIdBody request) {
        return taskService.cancel(taskId, request.requestId());
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    @GetMapping("/{taskId}/diagnostics")
    public TaskDiagnosticsResponse diagnostics(@PathVariable long taskId) {
        return taskService.diagnostics(taskId);
    }
}
