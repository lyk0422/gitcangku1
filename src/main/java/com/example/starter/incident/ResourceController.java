package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.LeaseAcquireRequest;
import com.example.starter.incident.dto.Requests.PreemptRequest;
import com.example.starter.incident.dto.Requests.PreemptionClosureRequest;
import com.example.starter.incident.dto.Requests.ResourceCreateRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.PreemptionClosureView;
import com.example.starter.incident.dto.Responses.PreemptionView;
import com.example.starter.incident.dto.Responses.ResourceUsageView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 共享资源与租约 REST API。
 * 资源定义与只读查询不要求指挥人；租约申请、任务开始与抢占要求 X-Actor-Id 为当前指挥人。
 */
@RestController
@RequestMapping("/api")
public class ResourceController {

    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    /** 创建共享资源池（容量为正整数）。 */
    @PostMapping("/resources")
    public ResponseEntity<ResourceView> createResource(@RequestBody ResourceCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createResource(req));
    }

    /** 查询全部共享资源及实时占用（只读）。 */
    @GetMapping("/resources")
    public List<ResourceView> listResources() {
        return service.listResources();
    }

    /** 查询单资源占用明细：资源本体 + 当前全部 ACTIVE 租约（只读）。 */
    @GetMapping("/resources/{resourceKey}")
    public ResourceUsageView resourceUsage(@PathVariable String resourceKey) {
        return service.resourceUsage(resourceKey);
    }

    /** 查询资源租约历史（全部状态，只读）。 */
    @GetMapping("/resources/{resourceKey}/leases")
    public List<LeaseView> leaseHistory(@PathVariable String resourceKey) {
        return service.leaseHistory(resourceKey);
    }

    /** 当前指挥人为 OPEN 且未开始的任务申请租约（提交任务版本与 leaseKey）。 */
    @PostMapping("/incidents/{incidentKey}/tasks/{taskKey}/leases")
    public LeaseView acquireLease(@PathVariable String incidentKey, @PathVariable String taskKey,
                                  @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody LeaseAcquireRequest req) {
        return service.acquireLease(incidentKey, taskKey, actor, req);
    }

    /** 任务开始：提交据以启动的 ACTIVE 租约键集合，任一失效即 409。 */
    @PostMapping("/incidents/{incidentKey}/tasks/{taskKey}/start")
    public TaskView startTask(@PathVariable String incidentKey, @PathVariable String taskKey,
                              @RequestHeader("X-Actor-Id") String actor,
                              @RequestBody TaskStartRequest req) {
        return service.startTask(incidentKey, taskKey, actor, req);
    }

    /** 提交依赖感知抢占计划：整单成功或整单 409/422。 */
    @PostMapping("/incidents/{incidentKey}/preemptions")
    public PreemptionView preempt(@PathVariable String incidentKey,
                                  @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody PreemptRequest req) {
        return service.preempt(incidentKey, actor, req);
    }

    /** 只读计算抢占闭包：列出必须同时抢占的全部受害租约，遇 STARTED 依赖链 422。 */
    @PostMapping("/incidents/{incidentKey}/preemptions/closure")
    public PreemptionClosureView preemptionClosure(@PathVariable String incidentKey,
                                                   @RequestBody PreemptionClosureRequest req) {
        return service.preemptionClosure(incidentKey, req);
    }
}
