package com.example.starter.web;

import com.example.starter.domain.Actor;
import com.example.starter.error.ForbiddenException;
import com.example.starter.idempotency.WriteOutcome;
import com.example.starter.service.ExperimentService;
import com.example.starter.web.dto.AllocationView;
import com.example.starter.web.dto.ApproveUnblindRequest;
import com.example.starter.web.dto.CloseExperimentRequest;
import com.example.starter.web.dto.CreateExperimentRequest;
import com.example.starter.web.dto.EnrollRequest;
import com.example.starter.web.dto.UnblindRequestRequest;
import com.example.starter.web.dto.UnblindResultView;
import com.example.starter.web.dto.WithdrawRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 实验盲法编码 API：分配/退组/关闭、普通查询及揭盲申请/批准/结果。 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService service;

    public ExperimentController(ExperimentService service) {
        this.service = service;
    }

    /** 创建实验（仅 COORDINATOR）。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createExperiment(Actor actor,
                                                   @Valid @RequestBody CreateExperimentRequest request) {
        requireCoordinator(actor);
        return toResponse(service.createExperiment(actor, request));
    }

    /** 参与者登记（仅 COORDINATOR）。 */
    @PostMapping(path = "/{experimentId}/enroll", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> enroll(Actor actor,
                                         @PathVariable String experimentId,
                                         @Valid @RequestBody EnrollRequest request) {
        requireCoordinator(actor);
        return toResponse(service.enroll(actor, experimentId, request));
    }

    /** 参与者退组（仅 COORDINATOR）。 */
    @PostMapping(path = "/{experimentId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> withdraw(Actor actor,
                                           @PathVariable String experimentId,
                                           @Valid @RequestBody WithdrawRequest request) {
        requireCoordinator(actor);
        return toResponse(service.withdraw(actor, experimentId, request));
    }

    /** 关闭实验（仅 COORDINATOR）。 */
    @PostMapping(path = "/{experimentId}/close", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> close(Actor actor,
                                        @PathVariable String experimentId,
                                        @Valid @RequestBody CloseExperimentRequest request) {
        requireCoordinator(actor);
        return toResponse(service.close(actor, experimentId, request));
    }

    /** 普通查询：只暴露盲码、区组号、参与者编号与退组状态（两种角色均可）。 */
    @GetMapping(path = "/{experimentId}/allocations/{participantId}")
    public AllocationView getAllocation(Actor actor,
                                        @PathVariable String experimentId,
                                        @PathVariable String participantId) {
        return service.getAllocation(actor, experimentId, participantId);
    }

    /** 提出揭盲申请（仅 COORDINATOR）。 */
    @PostMapping(path = "/{experimentId}/unblind-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> requestUnblind(Actor actor,
                                                 @PathVariable String experimentId,
                                                 @Valid @RequestBody UnblindRequestRequest request) {
        requireCoordinator(actor);
        return toResponse(service.requestUnblind(actor, experimentId, request));
    }

    /** 批准揭盲申请（仅 REVIEWER）。 */
    @PostMapping(path = "/{experimentId}/unblind-requests/{requestPk}/approve",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> approveUnblind(Actor actor,
                                                 @PathVariable String experimentId,
                                                 @PathVariable long requestPk,
                                                 @Valid @RequestBody ApproveUnblindRequest request) {
        if (actor.role() != Actor.Role.REVIEWER) {
            throw new ForbiddenException("only REVIEWER can approve unblind requests");
        }
        return toResponse(service.approveUnblind(actor, experimentId, requestPk, request));
    }

    /** 查询揭盲结果：仅申请人可见；未批准 409。 */
    @GetMapping(path = "/{experimentId}/unblind-requests/{requestPk}/result")
    public UnblindResultView getUnblindResult(Actor actor,
                                              @PathVariable String experimentId,
                                              @PathVariable long requestPk) {
        return service.getUnblindResult(actor, experimentId, requestPk);
    }

    private static void requireCoordinator(Actor actor) {
        if (actor.role() != Actor.Role.COORDINATOR) {
            throw new ForbiddenException("only COORDINATOR can perform this operation");
        }
    }

    private static ResponseEntity<String> toResponse(WriteOutcome outcome) {
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.responseJson());
    }
}
