package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.LeaseReturnRequest;
import com.example.starter.incident.dto.Requests.MutualAidRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Responses.IncidentLeasesView;
import com.example.starter.incident.dto.Responses.LeaseDetailView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.ResourceInventoryView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨事件互助资源征调 REST API（骨架，实现待补）。
 */
@RestController
@RequestMapping("/api/incidents/{incidentKey}/resources")
public class MutualAidController {

    private final MutualAidService service;

    public MutualAidController(MutualAidService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ResourceInventoryView> register(@PathVariable String incidentKey,
                                                          @RequestHeader("X-Actor-Id") String actor,
                                                          @RequestBody ResourceRegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(incidentKey, actor, req));
    }

    @PostMapping("/aid-requests")
    public LeaseView requestAid(@PathVariable String incidentKey,
                                @RequestHeader("X-Actor-Id") String actor,
                                @RequestBody MutualAidRequest req) {
        return service.requestAid(incidentKey, actor, req);
    }

    @PostMapping("/aid-requests/{requestId}/returns")
    public LeaseView returnResource(@PathVariable String incidentKey,
                                    @PathVariable String requestId,
                                    @RequestHeader("X-Actor-Id") String actor,
                                    @RequestBody LeaseReturnRequest req) {
        return service.returnResource(incidentKey, requestId, actor, req);
    }

    @GetMapping("/aid-requests/{requestId}")
    public LeaseDetailView getLease(@PathVariable String incidentKey,
                                    @PathVariable String requestId) {
        return service.getLease(incidentKey, requestId);
    }

    @GetMapping("/aid-requests")
    public IncidentLeasesView listLeases(@PathVariable String incidentKey) {
        return service.listLeases(incidentKey);
    }
}
