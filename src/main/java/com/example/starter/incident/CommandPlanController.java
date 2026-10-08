package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.PlanCancelRequest;
import com.example.starter.incident.dto.Requests.PlanCompensateRequest;
import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanStepExecuteRequest;
import com.example.starter.incident.dto.Responses.PlanView;
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
 * 联动处置指令计划 REST API。只操作合成状态，无外部 HTTP 调用。
 * 所有写操作要求 X-Actor-Id（非空操作者）与 X-Request-Id（全局请求 id）请求头；
 * 仅当前指挥人可写，交接后旧指挥返回 403，且权限校验先于幂等重放。
 */
@RestController
@RequestMapping("/api/incidents/{incidentKey}/plans")
public class CommandPlanController {

    private final CommandPlanService service;

    public CommandPlanController(CommandPlanService service) {
        this.service = service;
    }

    /** 当前指挥人创建最多 5 步的有序指令计划，初始 RUNNING。 */
    @PostMapping
    public ResponseEntity<PlanView> create(@PathVariable String incidentKey,
                                           @RequestHeader("X-Actor-Id") String actor,
                                           @RequestHeader("X-Request-Id") String requestId,
                                           @RequestBody PlanCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createPlan(incidentKey, actor, requestId, req));
    }

    /** 查询事件全部指令计划（含步骤原效果与每次补偿证据/诊断），只读。 */
    @GetMapping
    public List<PlanView> list(@PathVariable String incidentKey) {
        return service.listPlans(incidentKey);
    }

    /** 查询单张指令计划，只读。 */
    @GetMapping("/{planId}")
    public PlanView get(@PathVariable String incidentKey, @PathVariable long planId) {
        return service.getPlan(incidentKey, planId);
    }

    /** 显式执行当前下一步（原子 APPLIED）；跳步、重复旧步骤、取消后执行均 409。 */
    @PostMapping("/{planId}/steps/{stepKey}/execute")
    public PlanView execute(@PathVariable String incidentKey, @PathVariable long planId,
                            @PathVariable String stepKey,
                            @RequestHeader("X-Actor-Id") String actor,
                            @RequestHeader("X-Request-Id") String requestId,
                            @RequestBody PlanStepExecuteRequest req) {
        return service.executeStep(incidentKey, planId, stepKey, actor, requestId, req);
    }

    /** 取消 RUNNING/COMPLETED 计划进入 COMPENSATING（无已执行步骤则直接 CANCELLED）。 */
    @PostMapping("/{planId}/cancel")
    public PlanView cancel(@PathVariable String incidentKey, @PathVariable long planId,
                           @RequestHeader("X-Actor-Id") String actor,
                           @RequestHeader("X-Request-Id") String requestId,
                           @RequestBody PlanCancelRequest req) {
        return service.cancelPlan(incidentKey, planId, actor, requestId, req);
    }

    /** 逆序逐项补偿：evidence 非空成功，failureDiagnostic 非空仅记诊断不改 APPLIED。 */
    @PostMapping("/{planId}/steps/{stepKey}/compensate")
    public PlanView compensate(@PathVariable String incidentKey, @PathVariable long planId,
                               @PathVariable String stepKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestHeader("X-Request-Id") String requestId,
                               @RequestBody PlanCompensateRequest req) {
        return service.compensateStep(incidentKey, planId, stepKey, actor, requestId, req);
    }
}
