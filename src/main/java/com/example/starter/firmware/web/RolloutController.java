package com.example.starter.firmware.web;

import com.example.starter.firmware.dto.CancelRolloutRequest;
import com.example.starter.firmware.dto.CreateRolloutRequest;
import com.example.starter.firmware.dto.ExpandRolloutRequest;
import com.example.starter.firmware.dto.PullTaskRequest;
import com.example.starter.firmware.dto.TaskResponse;
import com.example.starter.firmware.service.ApiResult;
import com.example.starter.firmware.service.RolloutService;
import com.example.starter.firmware.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 灰度发布单接口：创建、扩量、取消、设备拉取、任务明细查询。
 */
@RestController
@RequestMapping("/api/rollouts")
public class RolloutController {

    private final RolloutService rolloutService;
    private final TaskService taskService;

    public RolloutController(RolloutService rolloutService, TaskService taskService) {
        this.rolloutService = rolloutService;
        this.taskService = taskService;
    }

    /**
     * 创建发布单：版本从 1 开始，同型号至多一张 ACTIVE。
     */
    @PostMapping
    public ResponseEntity<Object> create(@Valid @RequestBody CreateRolloutRequest request) {
        ApiResult result = rolloutService.create(request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /**
     * 扩量：比例只增不减，携带 expectedVersion，成功版本加一。
     */
    @PostMapping("/{id}/expand")
    public ResponseEntity<Object> expand(@PathVariable long id,
                                         @Valid @RequestBody ExpandRolloutRequest request) {
        ApiResult result = rolloutService.expand(id, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /**
     * 取消发布单：未终结任务置为 CANCELLED。
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable long id,
                                         @Valid @RequestBody CancelRolloutRequest request) {
        ApiResult result = rolloutService.cancel(id, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /**
     * 设备拉取任务：已有任务直接返回，否则按投放条件创建。
     */
    @PostMapping("/{id}/pull")
    public ResponseEntity<Object> pull(@PathVariable long id,
                                       @Valid @RequestBody PullTaskRequest request) {
        ApiResult result = taskService.pull(id, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    /**
     * 查询发布单下全部任务明细。
     */
    @GetMapping("/{id}/tasks")
    public List<TaskResponse> listTasks(@PathVariable long id) {
        return taskService.listByRollout(id);
    }
}
