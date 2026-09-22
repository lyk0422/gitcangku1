package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.TaskGroupView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分组处置任务 REST API。全部写操作要求 X-Actor-Id 请求头标识操作时的当前指挥人，
 * 并携带 commandKey 实现同参重放、异参 409、失败不占键。
 */
@RestController
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    /**
     * 在指定事件下创建处置任务（含 0～5 个跨事件阻塞依赖）。
     */
    @PostMapping("/api/incidents/{incidentKey}/tasks")
    public ResponseEntity<TaskView> create(@PathVariable String incidentKey,
                                           @RequestHeader("X-Actor-Id") String actor,
                                           @RequestBody TaskCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTask(incidentKey, actor, req));
    }

    /**
     * 按事件查询任务，结果按 groupCode 分组（只读，不隐式写入）。
     */
    @GetMapping("/api/incidents/{incidentKey}/tasks")
    public List<TaskGroupView> list(@PathVariable String incidentKey) {
        return service.listTasks(incidentKey);
    }

    /**
     * 查询单任务明细及实时阻塞状态（只读，不隐式写入）。
     */
    @GetMapping("/api/incidents/{incidentKey}/tasks/{taskKey}")
    public TaskView get(@PathVariable String incidentKey, @PathVariable String taskKey) {
        return service.getTask(incidentKey, taskKey);
    }

    /**
     * 完成任务：全部阻塞事件解除后方可完成，否则 409 并返回仍未解除的事件列表。
     */
    @PostMapping("/api/incidents/{incidentKey}/tasks/{taskKey}/complete")
    public TaskView complete(@PathVariable String incidentKey, @PathVariable String taskKey,
                             @RequestHeader("X-Actor-Id") String actor,
                             @RequestBody TaskActionRequest req) {
        return service.completeTask(incidentKey, taskKey, actor, req);
    }

    /**
     * 取消任务：仅 OPEN 可取消，终态重复取消返回首次结果。
     */
    @PostMapping("/api/incidents/{incidentKey}/tasks/{taskKey}/cancel")
    public TaskView cancel(@PathVariable String incidentKey, @PathVariable String taskKey,
                           @RequestHeader("X-Actor-Id") String actor,
                           @RequestBody TaskActionRequest req) {
        return service.cancelTask(incidentKey, taskKey, actor, req);
    }
}
