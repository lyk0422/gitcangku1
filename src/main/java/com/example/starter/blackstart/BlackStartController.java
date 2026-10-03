package com.example.starter.blackstart;

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
 * 黑启动 REST 接口：计划创建、步骤确认、终止与查询。不连接实际电力设备。
 */
@RestController
@RequestMapping("/api/black-start/plans")
public class BlackStartController {

    private final BlackStartService service;

    public BlackStartController(BlackStartService service) {
        this.service = service;
    }

    /**
     * 创建新的黑启动计划，返回 201 与初始状态。
     */
    @PostMapping
    public ResponseEntity<PlanView> createPlan(@Valid @RequestBody(required = false) CreatePlanRequest request) {
        PlanView view = service.createPlan(request == null ? null : request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * 查询计划：当前步骤、每步读数、拒绝理由和终止或完成时刻。
     */
    @GetMapping("/{planId}")
    public PlanView getPlan(@PathVariable long planId) {
        return service.getPlan(planId);
    }

    /**
     * 确认指定步骤。成功 200，读数越界或设备状态错误 422（记录原因且不推进步骤号），
     * requestId 异参或状态冲突 409。
     */
    @PostMapping("/{planId}/steps/{stepNo}/confirmations")
    public ResponseEntity<ConfirmResponse> confirmStep(
            @PathVariable long planId,
            @PathVariable int stepNo,
            @Valid @RequestBody ConfirmStepRequest request) {
        BlackStartService.ConfirmOutcome outcome = service.confirmStep(planId, stepNo, request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.response());
    }

    /**
     * 终止计划。已终止时幂等返回当前状态，已启动时 409。
     */
    @PostMapping("/{planId}/termination")
    public PlanView terminatePlan(
            @PathVariable long planId,
            @Valid @RequestBody(required = false) TerminatePlanRequest request) {
        return service.terminatePlan(planId, request == null ? null : request.reason());
    }
}