package com.example.starter.plan.web;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.web.dto.ConnectionDiagnosisView;
import com.example.starter.plan.web.dto.ConnectionListItem;
import com.example.starter.plan.web.dto.ConnectionRiskView;
import com.example.starter.plan.web.dto.ConnectionView;
import com.example.starter.plan.web.dto.GuaranteeDecisionView;
import com.example.starter.plan.web.dto.GuaranteeRescheduleRequest;
import com.example.starter.plan.web.dto.GuaranteeRescheduleResponse;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.RegisterConnectionRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 铁路接续保障 API：接续登记/取消、批量保障改签，
 * 以及接续明细、风险历史、决策历史与诊断查询（只读，不改变状态）。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class ConnectionController {

    private final ConnectionService service;

    public ConnectionController(ConnectionService service) {
        this.service = service;
    }

    /**
     * 登记接续：两趟已发布计划，站点连续、同运营日或明确跨零点、保障分钟数满足。
     */
    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionView register(@Valid @RequestBody RegisterConnectionRequest request) {
        return service.register(request);
    }

    /**
     * 取消生效接续：关系保留为历史，触发的风险标记清除，历史风险记录不改写。
     */
    @PostMapping("/connections/{connectionKey}/cancel")
    public ConnectionView cancel(@PathVariable String connectionKey,
                                 @Valid @RequestBody PlanActionRequest request) {
        return service.cancel(connectionKey, request.requestKey());
    }

    /**
     * 批量保障改签：对选定风险后车整体延后，须同时满足线路时隙与全部接续分钟数；
     * 任一项不可行则整次 422 回滚。
     */
    @PostMapping("/connections/guarantee-reschedule")
    public GuaranteeRescheduleResponse batchGuaranteeReschedule(
            @Valid @RequestBody GuaranteeRescheduleRequest request) {
        return service.batchGuaranteeReschedule(request);
    }

    /**
     * 接续明细（生效接续含实时评估）。
     */
    @GetMapping("/connections/{connectionKey}")
    public ConnectionView getConnection(@PathVariable String connectionKey) {
        return service.getConnection(connectionKey);
    }

    /**
     * 计划参与的全部接续（含已取消历史，任一端角色）。
     */
    @GetMapping("/plans/{scheduleKey}/connections")
    public List<ConnectionListItem> listPlanConnections(@PathVariable String scheduleKey) {
        return service.listPlanConnections(scheduleKey);
    }

    /**
     * 计划作为后车的风险判定历史（只读，不改写）。
     */
    @GetMapping("/plans/{scheduleKey}/connection-risks")
    public List<ConnectionRiskView> listRisks(@PathVariable String scheduleKey) {
        return service.listRisks(scheduleKey);
    }

    /**
     * 计划接续诊断：当前风险标记与全部生效接续的实时评估（只读）。
     */
    @GetMapping("/plans/{scheduleKey}/connection-diagnosis")
    public ConnectionDiagnosisView diagnose(@PathVariable String scheduleKey) {
        return service.diagnose(scheduleKey);
    }

    /**
     * 计划作为后车的保障改签决策历史（只读，不改写）。
     */
    @GetMapping("/plans/{scheduleKey}/guarantee-decisions")
    public List<GuaranteeDecisionView> listDecisions(@PathVariable String scheduleKey) {
        return service.listDecisions(scheduleKey);
    }
}