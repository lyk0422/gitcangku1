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
 * 黑启动控制室接口：创建计划、步骤确认、终止与查询。不连接实际电力设备。
 */
@RestController
@RequestMapping("/api/blackstart/plans")
public class BlackStartController {

    private final BlackStartService service;

    public BlackStartController(BlackStartService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PlanView> createPlan() {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPlan());
    }

    @PostMapping("/{planId}/steps/{stepNo}/confirm")
    public ResponseEntity<ConfirmStepResponse> confirmStep(
            @PathVariable long planId,
            @PathVariable int stepNo,
            @Valid @RequestBody ConfirmStepRequest request) {
        ConfirmStepResponse response = service.confirmStep(planId, stepNo, request);
        if (StepAttempt.OUTCOME_SUCCESS.equals(response.outcome())) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.unprocessableEntity().body(response);
    }

    @PostMapping("/{planId}/terminate")
    public ResponseEntity<PlanView> terminate(
            @PathVariable long planId,
            @Valid @RequestBody TerminateRequest request) {
        return ResponseEntity.ok(service.terminate(planId, request));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<PlanView> getPlan(@PathVariable long planId) {
        return ResponseEntity.ok(service.getPlan(planId));
    }
}