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
 * 投放任务：分片接收摘要、安装回执、撤销与完整性诊断。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 逐分片或批量提交接收摘要；服务端按完整最终集合裁决，返回实际值、要求值、差额与聚合结果。
     */
    @PostMapping("/{taskId}/fragments")
    public FragmentReceiptResponse receiveFragments(@PathVariable long taskId,
                                                     @Valid @RequestBody FragmentReceiptRequest request) {
        return taskService.receiveFragments(taskId, request);
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    /**
     * 撤销未安装任务；安装终态返回 409，重复撤销幂等。
     */
    @PostMapping("/{taskId}/cancel")
    public TaskView cancel(@PathVariable long taskId, @Valid @RequestBody RequestIdBody request) {
        return taskService.cancelTask(taskId, request.requestId());
    }

    /**
     * 完整性诊断（只读）：登记清单、各代次接收证据与事件流水。
     */
    @GetMapping("/{taskId}/diagnostics")
    public TaskDiagnosticsResponse diagnostics(@PathVariable long taskId) {
        return taskService.diagnostics(taskId);
    }
}
