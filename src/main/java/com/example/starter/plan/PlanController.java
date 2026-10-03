package com.example.starter.plan;

import com.example.starter.plan.PlanRequests.ConfirmationRequest;
import com.example.starter.plan.PlanRequests.CreatePlanRequest;
import com.example.starter.plan.PlanRequests.EntryRequest;
import com.example.starter.plan.PlanRequests.ExecutionReportRequest;
import com.example.starter.plan.PlanRequests.IssueCommandRequest;
import com.example.starter.plan.PlanRequests.ZoneClearRequest;
import com.example.starter.plan.PlanViews.CommandView;
import com.example.starter.plan.PlanViews.ConfirmationView;
import com.example.starter.plan.PlanViews.EntryView;
import com.example.starter.plan.PlanViews.ExecutionOutcome;
import com.example.starter.plan.PlanViews.ExecutionView;
import com.example.starter.plan.PlanViews.IssueOutcome;
import com.example.starter.plan.PlanViews.PlanDetail;
import com.example.starter.plan.PlanViews.PlanStateView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 清场爆破合成指令 REST 接口，不接入真实起爆设备。
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    /** 创建清场计划（固定边界与检查点清单）。 */
    @PostMapping("/plans")
    public ResponseEntity<PlanStateView> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createPlan(request.name(), request.boundary(), request.checkpoints()));
    }

    /** 查询计划：检查轮次、各点确认时刻、指令有效期与最终状态。 */
    @GetMapping("/plans/{planId}")
    public PlanDetail getPlan(@PathVariable long planId) {
        return service.getPlan(planId);
    }

    /** 记录检查点确认（人数为零）。 */
    @PostMapping("/plans/{planId}/confirmations")
    public ResponseEntity<ConfirmationView> confirm(@PathVariable long planId,
            @Valid @RequestBody ConfirmationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.confirm(planId,
                request.checkpointCode(), request.confirmedBy(), request.headcount()));
    }

    /** 上报人员重新进入：未执行指令失效，清场轮次递增。 */
    @PostMapping("/plans/{planId}/entries")
    public ResponseEntity<EntryView> recordEntry(@PathVariable long planId,
            @Valid @RequestBody EntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.recordEntry(planId, request.detectedBy()));
    }

    /** 重新清场：解除有人标记，之后须产生新一轮确认。 */
    @PostMapping("/plans/{planId}/zone-clear")
    public PlanStateView clearZone(@PathVariable long planId,
            @Valid @RequestBody ZoneClearRequest request) {
        return service.clearZone(planId, request.clearedBy());
    }

    /** 签发合成爆破指令（幂等：同 requestId 同参重放，异参 409）。 */
    @PostMapping("/plans/{planId}/commands")
    public ResponseEntity<CommandView> issueCommand(@PathVariable long planId,
            @Valid @RequestBody IssueCommandRequest request) {
        IssueOutcome outcome = service.issueCommand(planId, request.requestId(),
                request.issuedBy());
        HttpStatus status = outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(outcome.view());
    }

    /** 执行回报：仅允许引用未过期且未撤销的指令（幂等同上）。 */
    @PostMapping("/commands/{commandId}/executions")
    public ResponseEntity<ExecutionView> reportExecution(@PathVariable long commandId,
            @Valid @RequestBody ExecutionReportRequest request) {
        ExecutionOutcome outcome = service.reportExecution(commandId, request.requestId(),
                request.reportedBy(), request.result());
        HttpStatus status = outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(outcome.view());
    }
}