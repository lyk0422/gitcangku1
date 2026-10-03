package com.example.starter.plan;

import com.example.starter.plan.PlanDtos.CommandView;
import com.example.starter.plan.PlanDtos.ConfirmRequest;
import com.example.starter.plan.PlanDtos.ConfirmationView;
import com.example.starter.plan.PlanDtos.CreatePlanRequest;
import com.example.starter.plan.PlanDtos.ExecuteRequest;
import com.example.starter.plan.PlanDtos.IssueRequest;
import com.example.starter.plan.PlanDtos.PersonEntryRequest;
import com.example.starter.plan.PlanDtos.PersonEntryView;
import com.example.starter.plan.PlanDtos.PlanView;
import com.example.starter.plan.PlanDtos.ReportView;
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
 * 清场业务 REST 接口；仅维护合成指令状态，不接入真实起爆设备。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PlanView> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPlan(request));
    }

    @GetMapping("/{planId}")
    public PlanView getPlan(@PathVariable long planId) {
        return service.getPlan(planId);
    }

    @PostMapping("/{planId}/checkpoints/{checkpointCode}/confirmation")
    public ConfirmationView confirmCheckpoint(@PathVariable long planId,
                                              @PathVariable String checkpointCode,
                                              @Valid @RequestBody ConfirmRequest request) {
        return service.confirmCheckpoint(planId, checkpointCode, request);
    }

    @PostMapping("/{planId}/person-entries")
    public ResponseEntity<PersonEntryView> recordPersonEntry(@PathVariable long planId,
                                                             @Valid @RequestBody PersonEntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recordPersonEntry(planId, request));
    }

    @PostMapping("/{planId}/commands")
    public ResponseEntity<CommandView> issueCommand(@PathVariable long planId,
                                                    @Valid @RequestBody IssueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issueCommand(planId, request));
    }

    @PostMapping("/{planId}/commands/{commandId}/execution")
    public ResponseEntity<ReportView> reportExecution(@PathVariable long planId,
                                                      @PathVariable long commandId,
                                                      @Valid @RequestBody ExecuteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reportExecution(planId, commandId, request));
    }
}