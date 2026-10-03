package com.example.starter.plan.web;

import com.example.starter.plan.service.ConnectionService;
import com.example.starter.plan.web.dto.BatchGuaranteeRequest;
import com.example.starter.plan.web.dto.BatchGuaranteeResponse;
import com.example.starter.plan.web.dto.ConnectionDiagnosticsView;
import com.example.starter.plan.web.dto.ConnectionEventView;
import com.example.starter.plan.web.dto.ConnectionRegisterRequest;
import com.example.starter.plan.web.dto.ConnectionView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 铁路接续保障 API：接续登记/取消、批量保障改签，
 * 以及接续明细、历史事件与计划接续诊断查询（查询不改变状态）。
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
     * 登记接续关系：两趟已发布计划，站点连续、保障分钟数合法且登记时已满足。
     */
    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionView register(@Valid @RequestBody ConnectionRegisterRequest request) {
        return service.register(request);
    }

    /**
     * 取消接续：仅置状态不删行，历史事件保留。
     */
    @PostMapping("/connections/{id}/cancel")
    public ConnectionView cancel(@PathVariable long id,
                                 @Valid @RequestBody PlanActionRequest request) {
        return service.cancel(id, request.requestKey());
    }

    /**
     * 批量保障改签：对选定的一组风险后车整体改签，任一计划找不到
     * 不冲突且满足全部接续分钟数的时间窗时整次 422 回滚。
     */
    @PostMapping("/connections/batch-reschedule")
    public BatchGuaranteeResponse batchReschedule(@Valid @RequestBody BatchGuaranteeRequest request) {
        return service.batchGuaranteeReschedule(request);
    }

    /**
     * 接续明细（含实时保障要求值、实际值与差额）。
     */
    @GetMapping("/connections/{id}")
    public ConnectionView getConnection(@PathVariable long id) {
        return service.getConnection(id);
    }

    /**
     * 按计划业务键过滤接续列表（至少一个过滤条件）。
     */
    @GetMapping("/connections")
    public List<ConnectionView> listConnections(
            @RequestParam(required = false) String predecessorKey,
            @RequestParam(required = false) String successorKey) {
        return service.listConnections(predecessorKey, successorKey);
    }

    /**
     * 接续历史事件（只追加不改写），按发生顺序返回。
     */
    @GetMapping("/connections/{id}/history")
    public List<ConnectionEventView> getHistory(@PathVariable long id) {
        return service.getHistory(id);
    }

    /**
     * 计划接续诊断：当前风险标记、全部接续的实时保障差额与未解除的风险时间窗。
     */
    @GetMapping("/plans/{scheduleKey}/connection-diagnostics")
    public ConnectionDiagnosticsView getDiagnostics(@PathVariable String scheduleKey) {
        return service.getDiagnostics(scheduleKey);
    }
}