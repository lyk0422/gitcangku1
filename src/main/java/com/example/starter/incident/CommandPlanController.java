package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.PlanCancelRequest;
import com.example.starter.incident.dto.Requests.PlanCompensateRequest;
import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanExecuteRequest;
import com.example.starter.incident.dto.Responses.IncidentPlansView;
import com.example.starter.incident.dto.Responses.PlanView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指令计划 REST API：只操作合成状态的有序计划，提供执行、取消与补偿路径。
 * 写操作均要求 X-Actor-Id 请求头标识操作人（须为当前指挥人，否则 403）。
 */
@RestController
@RequestMapping("/api/incidents/{incidentKey}/plans")
public class CommandPlanController {

    private final CommandPlanService service;

    public CommandPlanController(CommandPlanService service) {
        this.service = service;
    }

    /**
     * 创建指令计划（仅当前指挥人；1~5 个有序步骤）。
     */
    @PostMapping
    public PlanView createPlan(@PathVariable String incidentKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody PlanCreateRequest req) {
        return service.createPlan(incidentKey, actor, req);
    }

    /**
     * 按事件查询指令计划列表（只读）。
     */
    @GetMapping
    public IncidentPlansView listPlans(@PathVariable String incidentKey) {
        return service.listPlans(incidentKey);
    }

    /**
     * 查询单个指令计划明细（含步骤与补偿证据，只读）。
     */
    @GetMapping("/{planKey}")
    public PlanView getPlan(@PathVariable String incidentKey, @PathVariable String planKey) {
        return service.getPlan(incidentKey, planKey);
    }

    /**
     * 取消计划（仅当前指挥人；RUNNING/COMPLETED → COMPENSATING/CANCELLED）。
     */
    @PostMapping("/{planKey}/cancel")
    public PlanView cancelPlan(@PathVariable String incidentKey, @PathVariable String planKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody PlanCancelRequest req) {
        return service.cancelPlan(incidentKey, planKey, actor, req);
    }

    /**
     * 执行步骤（仅当前指挥人；只允许当前下一步，原子记录 APPLIED）。
     */
    @PostMapping("/{planKey}/steps/{stepKey}/execute")
    public PlanView executeStep(@PathVariable String incidentKey, @PathVariable String planKey,
                                @PathVariable String stepKey,
                                @RequestHeader("X-Actor-Id") String actor,
                                @RequestBody PlanExecuteRequest req) {
        return service.executeStep(incidentKey, planKey, stepKey, actor, req);
    }

    /**
     * 补偿步骤（仅当前指挥人；按已执行步骤逆序逐项补偿）。
     */
    @PostMapping("/{planKey}/steps/{stepKey}/compensate")
    public PlanView compensateStep(@PathVariable String incidentKey, @PathVariable String planKey,
                                   @PathVariable String stepKey,
                                   @RequestHeader("X-Actor-Id") String actor,
                                   @RequestBody PlanCompensateRequest req) {
        return service.compensateStep(incidentKey, planKey, stepKey, actor, req);
    }
}
