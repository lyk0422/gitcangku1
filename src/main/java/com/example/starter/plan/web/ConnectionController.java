package com.example.starter.plan.web;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.web.dto.ConnectionEventView;
import com.example.starter.plan.web.dto.ConnectionRiskView;
import com.example.starter.plan.web.dto.ConnectionView;
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
 * 铁路旅客接续保障 API：接续登记/取消、批量保障改签、
 * 接续明细、计划接续列表、接续事件历史与风险诊断查询。
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
     * 登记接续关系：两趟已发布计划、站点连续、同运营日或明确跨零点、保障分钟数充足。
     */
    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionView register(@Valid @RequestBody RegisterConnectionRequest request) {
        return service.register(request);
    }

    /**
     * 取消生效中的接续；历史风险记录不改写，后车风险标记同事务重估。
     */
    @PostMapping("/connections/{connectionId}/cancel")
    public ConnectionView cancel(@PathVariable long connectionId,
                                 @Valid @RequestBody PlanActionRequest request) {
        return service.cancelConnection(connectionId, request.requestKey());
    }

    /**
     * 批量保障改签：同一事务内将选定风险后车整体延后至同时满足
     * 线路时隙与全部接续分钟数的时刻；任一计划不可行则整次 422 回滚。
     */
    @PostMapping("/connections/guarantee-reschedule")
    public GuaranteeRescheduleResponse guaranteeReschedule(
            @Valid @RequestBody GuaranteeRescheduleRequest request) {
        return service.guaranteeReschedule(request);
    }

    /**
     * 接续明细（只读）。
     */
    @GetMapping("/connections/{connectionId}")
    public ConnectionView getConnection(@PathVariable long connectionId) {
        return service.getConnection(connectionId);
    }

    /**
     * 指定计划参与的全部接续（含已取消），按登记顺序（只读）。
     */
    @GetMapping("/plans/{scheduleKey}/connections")
    public List<ConnectionView> listConnections(@PathVariable String scheduleKey) {
        return service.listConnectionsForPlan(scheduleKey);
    }

    /**
     * 指定计划的接续事件历史（只增不改的业务证据），按发生顺序（只读）。
     */
    @GetMapping("/plans/{scheduleKey}/connection-events")
    public List<ConnectionEventView> listEvents(@PathVariable String scheduleKey) {
        return service.listEventsForPlan(scheduleKey);
    }

    /**
     * 指定计划的接续风险诊断：当前标记、可选改签时间窗与实时保障评估（只读）。
     */
    @GetMapping("/plans/{scheduleKey}/connection-risk")
    public ConnectionRiskView getRisk(@PathVariable String scheduleKey) {
        return service.getRisk(scheduleKey);
    }
}
