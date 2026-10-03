package com.example.starter.calibration;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收任务接口。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 创建接收任务。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskView create(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.createTask(request);
    }

    /** 查询任务及其绑定版本。 */
    @GetMapping("/{id}")
    public TaskView get(@PathVariable long id) {
        return taskService.getTask(id);
    }

    /** 标记任务完成。 */
    @PostMapping("/{id}/complete")
    public TaskView complete(@PathVariable long id) {
        return taskService.completeTask(id);
    }
}