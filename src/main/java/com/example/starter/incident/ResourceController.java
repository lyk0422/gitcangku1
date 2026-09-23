package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.LeaseRequest;
import com.example.starter.incident.dto.Requests.PreemptRequest;
import com.example.starter.incident.dto.Requests.ResourceCreateRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Responses.LeaseHistoryView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.PreemptionClosureView;
import com.example.starter.incident.dto.Responses.PreemptionView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨事件共享资源 REST API。写操作均要求 X-Actor-Id 请求头标识操作人；
 * 租约申请/抢占/任务启动要求操作人为相关事件的当前指挥人。
 */
@RestController
public class ResourceController {

    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    /**
     * 创建共享资源：capacity 为正整数，resourceKey 全局唯一。
     */
    @PostMapping("/api/resources")
    public ResponseEntity<ResourceView> createResource(@RequestHeader("X-Actor-Id") String actor,
                                                       @RequestBody ResourceCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createResource(actor, req));
    }

    /**
     * 查询资源占用（容量、ACTIVE 单位合计、剩余单位、ACTIVE 租约列表）。只读。
     */
    @GetMapping("/api/resources/{resourceKey}")
    public ResourceView getResource(@PathVariable String resourceKey) {
        return service.getResource(resourceKey);
    }

    /**
     * 查询资源全部租约历史（含 RELEASED/REVOKED）。只读。
     */
    @GetMapping("/api/resources/{resourceKey}/leases")
    public LeaseHistoryView listLeases(@PathVariable String resourceKey) {
        return service.listLeases(resourceKey);
    }

    /**
     * 抢占闭包查询（只读）：给定拟撤销的受害租约（leases 查询参数，可重复或逗号分隔），
     * 返回反向依赖闭包要求但未列出的 ACTIVE 租约及闭包中已 STARTED 的任务。
     */
    @GetMapping("/api/resources/{resourceKey}/preemption-closure")
    public PreemptionClosureView preemptionClosure(@PathVariable String resourceKey,
                                                   @RequestParam List<String> leases) {
        return service.preemptionClosure(resourceKey, leases);
    }

    /**
     * 申请租约（仅当前指挥人；任务须 OPEN 且版本一致；容量不足 409）。
     */
    @PostMapping("/api/incidents/{incidentKey}/tasks/{taskKey}/leases")
    public LeaseView requestLease(@PathVariable String incidentKey,
                                  @PathVariable String taskKey,
                                  @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody LeaseRequest req) {
        return service.requestLease(incidentKey, taskKey, actor, req);
    }

    /**
     * 提交抢占计划（仅当前指挥人；严重级别须严格更高；原子撤销受害租约并授予新租约）。
     */
    @PostMapping("/api/incidents/{incidentKey}/tasks/{taskKey}/preempt")
    public PreemptionView preempt(@PathVariable String incidentKey,
                                  @PathVariable String taskKey,
                                  @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody PreemptRequest req) {
        return service.preempt(incidentKey, taskKey, actor, req);
    }

    /**
     * 标记任务 STARTED（仅当前指挥人；任务须持有 ACTIVE 租约）。
     */
    @PostMapping("/api/incidents/{incidentKey}/tasks/{taskKey}/start")
    public TaskView startTask(@PathVariable String incidentKey,
                              @PathVariable String taskKey,
                              @RequestHeader("X-Actor-Id") String actor,
                              @RequestBody TaskActionRequest req) {
        return service.startTask(incidentKey, taskKey, actor, req);
    }
}
