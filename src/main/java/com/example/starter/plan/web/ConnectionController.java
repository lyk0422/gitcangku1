package com.example.starter.plan.web;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.web.dto.BatchGuaranteeRequest;
import com.example.starter.plan.web.dto.BatchGuaranteeResponse;
import com.example.starter.plan.web.dto.ConnectionDecisionView;
import com.example.starter.plan.web.dto.ConnectionDiagnosticsView;
import com.example.starter.plan.web.dto.ConnectionRiskView;
import com.example.starter.plan.web.dto.ConnectionView;
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
 * 铁路旅客接续保障 API：接续登记/取消、批量保障改签，
 * 以及接续明细、风险历史、决策历史与诊断查询（查询均只读）。
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
     * 登记接续保障：两趟已发布计划，后车始发不早于前车终到加保障分钟数。
     */
    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionView register(@Valid @RequestBody RegisterConnectionRequest request) {
        return service.register(request);
    }

    /**
     * 取消接续登记：历史风险与决策记录不改写。
     */
    @PostMapping("/connections/{connectionId}/cancel")
    public ConnectionView cancel(@PathVariable long connectionId,
                                 @Valid @RequestBody PlanActionRequest request) {
        return service.cancel(connectionId, request.requestKey());
    }

    /**
     * 批量保障改签：对选定的一组风险后车逐条原子改签，任一找不到不冲突时间窗
     * 或不满足全部接续分钟数时整次 422 回滚。
     */
    @PostMapping("/connections/batch-guarantee")
    public BatchGuaranteeResponse batchGuarantee(@Valid @RequestBody BatchGuaranteeRequest request) {
        return service.batchGuarantee(request);
    }

    /**
     * 接续登记明细（含实时保障评估）。
     */
    @GetMapping("/connections/{connectionId}")
    public ConnectionView getConnection(@PathVariable long connectionId) {
        return service.getConnection(connectionId);
    }

    /**
     * 指定计划（任一侧）的全部接续登记。
     */
    @GetMapping("/plans/{scheduleKey}/connections")
    public List<ConnectionView> getConnectionsForPlan(@PathVariable String scheduleKey) {
        return service.getConnectionsForPlan(scheduleKey);
    }

    /**
     * 指定计划作为后车的全部风险判定历史（不可变）。
     */
    @GetMapping("/plans/{scheduleKey}/connection-risks")
    public List<ConnectionRiskView> getRisksForPlan(@PathVariable String scheduleKey) {
        return service.getRisksForPlan(scheduleKey);
    }

    /**
     * 指定计划相关的全部批量保障改签决策历史（不可变）。
     */
    @GetMapping("/plans/{scheduleKey}/connection-decisions")
    public List<ConnectionDecisionView> getDecisionsForPlan(@PathVariable String scheduleKey) {
        return service.getDecisionsForPlan(scheduleKey);
    }

    /**
     * 接续诊断汇总：计划快照、接续、风险与决策历史。只读。
     */
    @GetMapping("/plans/{scheduleKey}/connection-diagnostics")
    public ConnectionDiagnosticsView getDiagnostics(@PathVariable String scheduleKey) {
        return service.getDiagnostics(scheduleKey);
    }
}
