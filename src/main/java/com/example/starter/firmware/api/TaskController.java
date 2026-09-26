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
 * 投放任务：分片接收、安装回执与完整性明细查询。
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

    @PostMapping("/{taskId}/shards")
    public ShardReceiveResponse receiveShards(@PathVariable long taskId,
                                              @Valid @RequestBody ReceiveShardsRequest request) {
        return integrityService.receiveShards(taskId, request);
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    @GetMapping("/{taskId}/integrity")
    public TaskIntegrityView integrity(@PathVariable long taskId) {
        return integrityService.taskIntegrity(taskId);
    }
}
