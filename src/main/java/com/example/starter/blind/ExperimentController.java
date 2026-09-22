package com.example.starter.blind;

import com.example.starter.blind.dto.AssignRequest;
import com.example.starter.blind.dto.AssignmentView;
import com.example.starter.blind.dto.CreateExperimentRequest;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.dto.RequestIdOnlyRequest;
import com.example.starter.blind.dto.UnblindApplyRequest;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实验盲法分配与受控揭盲 API。操作者由 X-Actor-Id 与 X-Role 头提供，本题信任本地测试头。
 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService service;

    public ExperimentController(ExperimentService service) {
        this.service = service;
    }

    @PostMapping
    public ExperimentView create(@RequestHeader("X-Actor-Id") String actorId,
                                 @RequestHeader("X-Role") String role,
                                 @Valid @RequestBody CreateExperimentRequest req) {
        return service.createExperiment(actorId, ActorRole.fromHeader(role), req);
    }

    @PostMapping("/{experimentId}/assignments")
    public AssignmentView assign(@RequestHeader("X-Actor-Id") String actorId,
                                 @RequestHeader("X-Role") String role,
                                 @PathVariable String experimentId,
                                 @Valid @RequestBody AssignRequest req) {
        return service.assign(actorId, ActorRole.fromHeader(role), experimentId, req);
    }

    @PostMapping("/{experimentId}/assignments/{participantId}/withdraw")
    public AssignmentView withdraw(@RequestHeader("X-Actor-Id") String actorId,
                                   @RequestHeader("X-Role") String role,
                                   @PathVariable String experimentId,
                                   @PathVariable String participantId,
                                   @Valid @RequestBody RequestIdOnlyRequest req) {
        return service.withdraw(actorId, ActorRole.fromHeader(role), experimentId,
                participantId, req.requestId());
    }

    @PostMapping("/{experimentId}/close")
    public ExperimentView close(@RequestHeader("X-Actor-Id") String actorId,
                                @RequestHeader("X-Role") String role,
                                @PathVariable String experimentId,
                                @Valid @RequestBody RequestIdOnlyRequest req) {
        return service.close(actorId, ActorRole.fromHeader(role), experimentId, req.requestId());
    }

    @GetMapping("/{experimentId}/assignments/{participantId}")
    public AssignmentView getAssignment(@RequestHeader("X-Actor-Id") String actorId,
                                        @RequestHeader("X-Role") String role,
                                        @PathVariable String experimentId,
                                        @PathVariable String participantId) {
        ActorRole.fromHeader(role);
        return service.getAssignment(experimentId, participantId);
    }

    @PostMapping("/{experimentId}/unblind-requests")
    public UnblindRequestView applyUnblind(@RequestHeader("X-Actor-Id") String actorId,
                                           @RequestHeader("X-Role") String role,
                                           @PathVariable String experimentId,
                                           @Valid @RequestBody UnblindApplyRequest req) {
        return service.applyUnblind(actorId, ActorRole.fromHeader(role), experimentId, req);
    }

    @PostMapping("/{experimentId}/unblind-requests/{unblindId}/approve")
    public UnblindRequestView approveUnblind(@RequestHeader("X-Actor-Id") String actorId,
                                             @RequestHeader("X-Role") String role,
                                             @PathVariable String experimentId,
                                             @PathVariable String unblindId,
                                             @Valid @RequestBody RequestIdOnlyRequest req) {
        return service.approveUnblind(actorId, ActorRole.fromHeader(role), experimentId,
                unblindId, req.requestId());
    }

    @GetMapping("/{experimentId}/unblind-requests/{unblindId}/result")
    public UnblindResultView getUnblindResult(@RequestHeader("X-Actor-Id") String actorId,
                                              @RequestHeader("X-Role") String role,
                                              @PathVariable String experimentId,
                                              @PathVariable String unblindId) {
        ActorRole.fromHeader(role);
        return service.getUnblindResult(actorId, experimentId, unblindId);
    }
}
