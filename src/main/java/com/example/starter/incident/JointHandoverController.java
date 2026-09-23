package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverInitiateRequest;
import com.example.starter.incident.dto.Responses.HandoverDetailView;
import com.example.starter.incident.dto.Responses.HandoverHistoryView;
import com.example.starter.incident.dto.Responses.HandoverView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联合指挥交接 REST API（骨架，实现随后接入服务）。
 */
@RestController
@RequestMapping("/api/handovers")
public class JointHandoverController {

    private final JointHandoverService service;

    public JointHandoverController(JointHandoverService service) {
        this.service = service;
    }

    @PostMapping
    public HandoverView initiate(@RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody HandoverInitiateRequest req) {
        return service.initiate(actor, req);
    }

    @PostMapping("/{handoverKey}/accept")
    public HandoverView accept(@PathVariable String handoverKey,
                               @RequestHeader("X-Actor-Id") String actor,
                               @RequestBody HandoverAcceptRequest req) {
        return service.accept(actor, handoverKey, req);
    }

    @GetMapping("/{handoverKey}")
    public HandoverDetailView detail(@PathVariable String handoverKey) {
        return service.detail(handoverKey);
    }

    @GetMapping("/by-commander/{commander}")
    public HandoverHistoryView history(@PathVariable String commander) {
        return service.history(commander);
    }
}
