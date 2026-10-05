package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.TaskRenewalRequest;
import com.example.starter.incident.dto.Responses.RenewalView;
import com.example.starter.incident.dto.Responses.ResourceAssignmentView;
import com.example.starter.incident.dto.Responses.ResourceDiagnosticsView;
import com.example.starter.incident.dto.Responses.ResourceEventView;
import com.example.starter.incident.dto.Responses.TaskResourceHistoryView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件资源授权 REST API：资源分配、批量续期与只读明细/历史/诊断。
 * 写操作要求 X-Actor-Id 请求头：分配须为当前指挥人，续期须为不同于原分配人的授权角色。
 */
@RestController
@RequestMapping("/api/incidents")
public class ResourceController {

    private final ResourceAuthorizationService service;

    public ResourceController(ResourceAuthorizationService service) {
        this.service = service;
    }

    /**
     * 资源分配：携带 UTC 到期时刻（须晚于分配时刻）与初始授权版本。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/resources")
    public ResourceAssignmentView assign(@PathVariable String incidentKey,
                                         @PathVariable String taskKey,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         @RequestBody ResourceAssignRequest req) {
        return service.assign(incidentKey, taskKey, actor, req);
    }

    /**
     * 批量续期：一次可包含多个依赖任务；先验证完整依赖闭包，任一冲突 422 且不改状态。
     */
    @PostMapping("/{incidentKey}/tasks/renewals")
    public RenewalView renew(@PathVariable String incidentKey,
                             @RequestHeader("X-Actor-Id") String actor,
                             @RequestBody TaskRenewalRequest req) {
        return service.renew(incidentKey, actor, req);
    }

    /**
     * 查询单任务资源明细与历史（当前租约、授权证据、执行日志）。只读。
     */
    @GetMapping("/{incidentKey}/tasks/{taskKey}/resources")
    public TaskResourceHistoryView taskResourceHistory(@PathVariable String incidentKey,
                                                       @PathVariable String taskKey) {
        return service.taskResourceHistory(incidentKey, taskKey);
    }

    /**
     * 查询事件全部资源授权证据（按发生顺序）。只读。
     */
    @GetMapping("/{incidentKey}/resource-events")
    public List<ResourceEventView> listEvents(@PathVariable String incidentKey) {
        return service.listEvents(incidentKey);
    }

    /**
     * 资源诊断：按评估时刻给出各租约到期与占用情况。只读。
     */
    @GetMapping("/{incidentKey}/resources/diagnostics")
    public ResourceDiagnosticsView diagnostics(@PathVariable String incidentKey) {
        return service.diagnostics(incidentKey);
    }
}
