package com.example.starter.incident;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件指挥 API：上报、接管、两步交接、处置记录、状态变更与查询。
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IncidentView> report(@Validated @RequestBody ReportIncidentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.report(request));
    }

    @PostMapping("/{incidentKey}/take-command")
    public ResponseEntity<IncidentView> takeCommand(
            @PathVariable String incidentKey,
            @RequestHeader("X-Actor-Id") String actorId,
            @Validated @RequestBody TakeCommandRequest request) {
        CommandOutcome<IncidentView> outcome = service.takeCommand(incidentKey, actorId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/{incidentKey}/handovers")
    public ResponseEntity<IncidentView> initiateHandover(
            @PathVariable String incidentKey,
            @RequestHeader("X-Actor-Id") String actorId,
            @Validated @RequestBody InitiateHandoverRequest request) {
        CommandOutcome<IncidentView> outcome = service.initiateHandover(incidentKey, actorId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/{incidentKey}/handovers/accept")
    public ResponseEntity<IncidentView> acceptHandover(
            @PathVariable String incidentKey,
            @RequestHeader("X-Actor-Id") String actorId,
            @Validated @RequestBody AcceptHandoverRequest request) {
        CommandOutcome<IncidentView> outcome = service.acceptHandover(incidentKey, actorId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/{incidentKey}/actions")
    public ResponseEntity<ActionView> appendAction(
            @PathVariable String incidentKey,
            @RequestHeader("X-Actor-Id") String actorId,
            @Validated @RequestBody AppendActionRequest request) {
        CommandOutcome<ActionView> outcome = service.appendAction(incidentKey, actorId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/{incidentKey}/status")
    public ResponseEntity<IncidentView> changeStatus(
            @PathVariable String incidentKey,
            @RequestHeader("X-Actor-Id") String actorId,
            @Validated @RequestBody ChangeStatusRequest request) {
        CommandOutcome<IncidentView> outcome = service.changeStatus(incidentKey, actorId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @GetMapping("/{incidentKey}")
    public IncidentView getIncident(@PathVariable String incidentKey) {
        return service.getIncident(incidentKey);
    }

    @GetMapping("/{incidentKey}/history")
    public IncidentHistoryView getHistory(@PathVariable String incidentKey) {
        return service.getHistory(incidentKey);
    }
}
