package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放任务：回执、人工复核与任务冻结证书链版本查询。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final ReleaseService releaseService;

    public TaskController(TaskService taskService, TaskRepository taskRepository,
                          ReleaseService releaseService) {
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.releaseService = releaseService;
    }

    @PostMapping("/{taskId}/receipt")
    public TaskView receipt(@PathVariable long taskId, @Valid @RequestBody ReceiptRequest request) {
        return taskService.receipt(taskId, request);
    }

    @PostMapping("/{taskId}/review")
    public TaskView review(@PathVariable long taskId, @Valid @RequestBody TaskReviewRequest request) {
        return taskService.review(taskId, request);
    }

    /**
     * 查询任务冻结的证书链版本/指纹、回执复核状态与拒绝原因。
     */
    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable long taskId) {
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        ReleaseOrder order = releaseService.findOrder(task.releaseId());
        return TaskView.of(task, order);
    }
}
