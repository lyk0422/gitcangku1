package com.example.starter.plan.web;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.web.dto.BatchGuaranteeRequest;
import com.example.starter.plan.web.dto.BatchGuaranteeResponse;
import com.example.starter.plan.web.dto.ConnectionRegisterRequest;
import com.example.starter.plan.web.dto.ConnectionRiskView;
import com.example.starter.plan.web.dto.ConnectionView;
import com.example.starter.plan.web.dto.GuaranteeDecisionView;
import com.example.starter.plan.web.dto.PlanActionRequest;
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
 * 铁路接续保障 API：接续登记/取消/明细，计划维度的接续、风险历史与保障改签决策查询，
 * 以及批量保障改签。查询均为只读，不改变状态。
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
     * 登记接续关系：两趟已发布计划间，后车始发不早于前车终到加保障分钟数。
     */
    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionView register(@Valid @RequestBody ConnectionRegisterRequest request) {
        return service.register(request);
    }

    /**
     * 取消接续：历史风险记录不改写，后车风险标记按剩余生效接续重估。
     */
    @PostMapping("/connections/{connectionId}/cancel")
    public ConnectionView cancel(@PathVariable long connectionId,
                                 @Valid @RequestBody PlanActionRequest request) {
        return service.cancelConnection(connectionId, request.requestKey());
    }

    /**
     * 接续明细。
     */
    @GetMapping("/connections/{connectionId}")
    public ConnectionView getConnection(@PathVariable long connectionId) {
        return service.getConnection(connectionId);
    }

    /**
     * 批量保障改签：对选定的一组风险后车统一寻找同时满足线路时隙与全部接续分钟数的
     * 时间窗；任一计划找不到不冲突时间窗时整次 422 回滚。
     */
    @PostMapping("/connections/guarantee-reschedule")
    public BatchGuaranteeResponse batchGuarantee(@Valid @RequestBody BatchGuaranteeRequest request) {
        return service.batchGuarantee(request);
    }

    /**
     * 计划参与的全部接续（作为前车或后车，含已取消历史）。
     */
    @GetMapping("/plans/{scheduleKey}/connections")
    public List<ConnectionView> listConnections(@PathVariable String scheduleKey) {
        return service.listConnections(scheduleKey);
    }

    /**
     * 后车全部接续风险历史（追加式，不改写）。
     */
    @GetMapping("/plans/{scheduleKey}/connection-risks")
    public List<ConnectionRiskView> listRisks(@PathVariable String scheduleKey) {
        return service.listRisks(scheduleKey);
    }

    /**
     * 后车全部保障改签决策历史（追加式，不改写）。
     */
    @GetMapping("/plans/{scheduleKey}/guarantee-decisions")
    public List<GuaranteeDecisionView> listDecisions(@PathVariable String scheduleKey) {
        return service.listDecisions(scheduleKey);
    }
}
