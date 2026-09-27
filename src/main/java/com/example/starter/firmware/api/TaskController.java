package com.example.starter.firmware.api;

import com.example.starter.firmware.service.IntegrityQueryService;
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
 * 投放任务：回执、分片接收、撤销与完整性明细/历史/诊断查询。查询均为只读，不改变状态。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final IntegrityService integrityService;
    private final IntegrityQueryService integrityQueryService;

    public TaskController(TaskService taskService, IntegrityService integrityService,
                          IntegrityQueryService integrityQueryService) {
        this.taskService = taskService;
        this.integrityService = integrityService;
        this.integrityQueryService = integrityQueryService;
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    @PostMapping("/{taskId}/chunks")
    public TaskView receiveChunks(@PathVariable long taskId,
                                  @Valid @RequestBody ReceiveChunksRequest request) {
        return integrityService.receiveChunks(taskId, request.requestId(), request.chunks());
    }

    @PostMapping("/{taskId}/revoke")
    public TaskView revoke(@PathVariable long taskId, @Valid @RequestBody RequestIdBody request) {
        return taskService.revoke(taskId, request.requestId());
    }

    @GetMapping("/{taskId}")
    public TaskView detail(@PathVariable long taskId) {
        return taskService.detail(taskId);
    }

    @GetMapping("/{taskId}/integrity")
    public IntegrityDetailResponse integrityDetail(@PathVariable long taskId) {
        return integrityQueryService.detail(taskId);
    }

    @GetMapping("/{taskId}/integrity/history")
    public IntegrityHistoryResponse integrityHistory(@PathVariable long taskId,
                                                     @RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        return integrityQueryService.history(taskId, from, to);
    }

    @GetMapping("/{taskId}/integrity/diagnostics")
    public IntegrityDiagnosticsResponse integrityDiagnostics(@PathVariable long taskId) {
        return integrityQueryService.diagnostics(taskId);
    }
}
