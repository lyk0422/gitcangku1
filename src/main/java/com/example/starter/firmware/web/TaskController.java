package com.example.starter.firmware.web;

import com.example.starter.firmware.dto.ReceiptRequest;
import com.example.starter.firmware.dto.TaskResponse;
import com.example.starter.firmware.service.ApiResult;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放任务接口：回执与单任务查询。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 任务回执：首次回执终结任务，仅 SUCCESS 更新设备当前版本。
     */
    @PostMapping("/{id}/receipt")
    public ResponseEntity<Object> receipt(@PathVariable long id,
                                          @Valid @RequestBody ReceiptRequest request) {
        ApiResult result = taskService.receipt(id, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /**
     * 查询单个任务明细。
     */
    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable long id) {
        return taskService.getTask(id);
    }
}
