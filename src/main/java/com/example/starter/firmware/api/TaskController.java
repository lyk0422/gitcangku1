package com.example.starter.firmware.api;

import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放任务回执，安装握手任务的显式开始、取消请求与停止确认。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    @PostMapping("/{taskId}/start")
    public TaskView start(@PathVariable long taskId, @Valid @RequestBody TaskStartRequest request) {
        return taskService.start(taskId, request);
    }

    @PostMapping("/{taskId}/cancel-request")
    public TaskView cancelRequest(@PathVariable long taskId, @Valid @RequestBody TaskCancelRequest request) {
        return taskService.cancelRequest(taskId, request);
    }

    @PostMapping("/{taskId}/stop-confirm")
    public TaskView stopConfirm(@PathVariable long taskId, @Valid @RequestBody StopConfirmRequest request) {
        return taskService.stopConfirm(taskId, request);
    }
}
