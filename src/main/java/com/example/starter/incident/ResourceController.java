package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.ResourceRenewRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Responses.ResourceDiagnosticsView;
import com.example.starter.incident.dto.Responses.ResourceEventView;
import com.example.starter.incident.dto.Responses.ResourceLeaseView;
import com.example.starter.incident.dto.Responses.ResourceRenewalView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件资源授权 REST API：分配、开始/继续执行、续期、回收、到期检查与
 * 明细/历史/诊断查询。写操作（除续期与到期检查外）要求 X-Actor-Id 为当前指挥人；
 * 续期要求 X-Actor-Id 为不同于原分配人的授权角色。
 */
@RestController
@RequestMapping("/api/incidents")
public class ResourceController {

    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    /**
     * 资源分配：携带 UTC 到期时刻（须晚于分配时刻）与初始授权版本（>= 1）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/resource")
    public ResourceLeaseView assign(@PathVariable String incidentKey,
                                    @PathVariable String taskKey,
                                    @RequestHeader("X-Actor-Id") String actor,
                                    @RequestBody ResourceAssignRequest req) {
        return service.assign(incidentKey, taskKey, actor, req);
    }

    /**
     * 查询任务当前资源租约明细（含按查询时刻计算的 expired 派生标志，只读）。
     */
    @GetMapping("/{incidentKey}/tasks/{taskKey}/resource")
    public ResourceLeaseView getLease(@PathVariable String incidentKey,
                                      @PathVariable String taskKey) {
        return service.getLease(incidentKey, taskKey);
    }

    /**
     * 查询任务资源证据事件历史（分配/到期/续期/执行/拦截/回收，只读）。
     */
    @GetMapping("/{incidentKey}/tasks/{taskKey}/resource/events")
    public List<ResourceEventView> listEvents(@PathVariable String incidentKey,
                                              @PathVariable String taskKey) {
        return service.listEvents(incidentKey, taskKey);
    }

    /**
     * 事件级资源诊断：全部租约 + 任务状态 + 按查询时刻计算的到期标志（只读）。
     */
    @GetMapping("/{incidentKey}/resources")
    public ResourceDiagnosticsView diagnostics(@PathVariable String incidentKey) {
        return service.diagnostics(incidentKey);
    }

    /**
     * 开始执行：仅 OPEN 任务；授权到期门禁（到期固化 AUTHORIZATION_EXPIRED 并 422）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/start")
    public TaskView start(@PathVariable String incidentKey, @PathVariable String taskKey,
                          @RequestHeader("X-Actor-Id") String actor,
                          @RequestBody TaskActionRequest req) {
        return service.startTask(incidentKey, taskKey, actor, req);
    }

    /**
     * 继续执行：仅 IN_PROGRESS 任务；授权到期门禁（到期固化并 422）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/continue")
    public TaskView continueTask(@PathVariable String incidentKey, @PathVariable String taskKey,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody TaskActionRequest req) {
        return service.continueTask(incidentKey, taskKey, actor, req);
    }

    /**
     * 续期：一次可含多个依赖任务；先验证完整依赖闭包，任一冲突 422 且全部不变。
     * 续期人由 X-Actor-Id 指定，必须不同于各租约原分配人。
     */
    @PostMapping("/{incidentKey}/resources/renew")
    public ResourceRenewalView renew(@PathVariable String incidentKey,
                                     @RequestHeader("X-Actor-Id") String actor,
                                     @RequestBody ResourceRenewRequest req) {
        return service.renew(incidentKey, actor, req);
    }

    /**
     * 显式回收资源授权（仅当前指挥人）。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/resource/release")
    public ResourceLeaseView release(@PathVariable String incidentKey,
                                     @PathVariable String taskKey,
                                     @RequestHeader("X-Actor-Id") String actor,
                                     @RequestBody TaskActionRequest req) {
        return service.release(incidentKey, taskKey, actor, req);
    }

    /**
     * 授权到期检查：以注入时钟评估，到期则固化 AUTHORIZATION_EXPIRED 与证据事件；
     * 携带 commandKey，同键重放首次结果。
     */
    @PostMapping("/{incidentKey}/tasks/{taskKey}/authorization/check")
    public ResourceLeaseView checkAuthorization(@PathVariable String incidentKey,
                                                @PathVariable String taskKey,
                                                @RequestBody TaskActionRequest req) {
        return service.checkAuthorization(incidentKey, taskKey, req);
    }
}
