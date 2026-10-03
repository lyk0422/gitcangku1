package com.example.starter.web;

import com.example.starter.domain.ReceiveTask;
import com.example.starter.domain.TaskBinding;
import com.example.starter.service.BindingService;
import com.example.starter.web.dto.BindRequest;
import com.example.starter.web.dto.CreateTaskRequest;
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
 * 任务与绑定接口：登记接收任务、幂等绑定、查询绑定结果。
 */
@RestController
@RequestMapping("/api")
public class BindingController {

    private final BindingService bindingService;

    public BindingController(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    /** 登记接收任务。 */
    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiveTask createTask(@Valid @RequestBody CreateTaskRequest request) {
        return bindingService.createTask(request);
    }

    /** 查询任务。 */
    @GetMapping("/tasks/{id}")
    public ReceiveTask getTask(@PathVariable long id) {
        return bindingService.getTask(id);
    }

    /** 绑定任务到校准记录；同 requestId 同参重放返回原绑定。 */
    @PostMapping("/bindings")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskBinding bind(@Valid @RequestBody BindRequest request) {
        return bindingService.bind(request);
    }

    /** 按 requestId 查询绑定。 */
    @GetMapping("/bindings/{requestId}")
    public TaskBinding getBinding(@PathVariable String requestId) {
        return bindingService.getByRequestId(requestId);
    }
}