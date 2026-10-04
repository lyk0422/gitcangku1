package com.example.starter.plan.web;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.web.dto.ConnectionHistoryResponse;
import com.example.starter.plan.web.dto.ConnectionResponse;
import com.example.starter.plan.web.dto.GuaranteeRescheduleRequest;
import com.example.starter.plan.web.dto.GuaranteeRescheduleResponse;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.RegisterConnectionRequest;
import com.example.starter.plan.web.dto.RiskPlanView;
import com.example.starter.plan.web.dto.SectionRequest;
import com.example.starter.plan.web.dto.SectionResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 铁路接续保障 API：区段目录、接续登记/取消、批量保障改签、
 * 接续明细/历史/计划关联与风险诊断查询。查询均为只读，不改变状态。
 */
@RestController
@RequestMapping("/api/v1")
public class ConnectionController {

    private final ConnectionService service;

    public ConnectionController(ConnectionService service) {
        this.service = service;
    }

    /**
     * 登记区段起讫站（接续站点连续性校验的依据）。
     */
    @PostMapping("/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public SectionResponse registerSection(@Valid @RequestBody SectionRequest request) {
        return service.registerSection(request);
    }

    /**
     * 登记接续关系：两趟已发布计划，后车始发不早于前车终到加保障分钟数。
     */
    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionResponse register(@Valid @RequestBody RegisterConnectionRequest request) {
        return service.register(request);
    }

    /**
     * 取消接续：仅更新当前状态并追加历史事件，不改写既有风险记录。
     */
    @PostMapping("/connections/{id}/cancel")
    public ConnectionResponse cancel(@PathVariable long id,
                                     @Valid @RequestBody PlanActionRequest request) {
        return service.cancelConnection(id, request.requestKey());
    }

    /**
     * 批量保障改签：对选定的一组风险后车整体寻找不冲突时间窗并平移；
     * 任一计划不可行则整次 422 回滚。
     */
    @PostMapping("/connections/guarantee-reschedule")
    public GuaranteeRescheduleResponse guaranteeReschedule(
            @Valid @RequestBody GuaranteeRescheduleRequest request) {
        return service.guaranteeReschedule(request);
    }

    /**
     * 诊断查询：当前标记 CONNECTION_AT_RISK 的计划及保障差额。
     */
    @GetMapping("/connections/risks")
    public List<RiskPlanView> listRisks() {
        return service.listRisks();
    }

    /**
     * 接续明细。
     */
    @GetMapping("/connections/{id}")
    public ConnectionResponse getConnection(@PathVariable long id) {
        return service.getConnection(id);
    }

    /**
     * 接续历史（登记、风险判定、改签决策、取消，按发生顺序）。
     */
    @GetMapping("/connections/{id}/history")
    public ConnectionHistoryResponse getHistory(@PathVariable long id) {
        return service.getHistory(id);
    }

    /**
     * 查询与指定计划相关的全部接续。
     */
    @GetMapping("/plans/{scheduleKey}/connections")
    public List<ConnectionResponse> getConnectionsForPlan(@PathVariable String scheduleKey) {
        return service.getConnectionsForPlan(scheduleKey);
    }
}