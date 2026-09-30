package com.example.starter.web;

import com.example.starter.service.ObservationService;
import com.example.starter.web.dto.ActionRequest;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.CancelResponse;
import com.example.starter.web.dto.PromotionView;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.SlotView;
import com.example.starter.web.dto.WaitlistView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观测窗口业务入口。
 */
@RestController
@RequestMapping("/api/observations")
public class ObservationController {

    private final ObservationService service;

    public ObservationController(ObservationService service) {
        this.service = service;
    }

    /** 提交窗口申请（进入候补）。 */
    @PostMapping("/requests")
    public ResponseEntity<RequestView> apply(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody ApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.apply(requestId, request));
    }

    /** 管理员批准，锁定整段窗口。 */
    @PostMapping("/requests/{requestNo}/approve")
    public ResponseEntity<RequestView> approve(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @PathVariable String requestNo) {
        return ResponseEntity.ok(service.approve(requestId, requestNo));
    }

    /** 研究组撤销自己尚未开始的申请。 */
    @PostMapping("/requests/{requestNo}/cancel")
    public CancelResponse cancel(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @Valid @RequestBody ActionRequest request) {
        return service.cancel(requestId, request.requestNo(), request.projectId());
    }

    /** 仪器已分配时间轴。 */
    @GetMapping("/instruments/{instrumentId}/timeline")
    public List<SlotView> timeline(@PathVariable String instrumentId) {
        return service.timeline(instrumentId);
    }

    /** 申请状态列表，可按仪器/项目过滤。 */
    @GetMapping("/requests")
    public List<RequestView> requests(
            @RequestParam(required = false) String instrumentId,
            @RequestParam(required = false) String projectId) {
        return service.requests(instrumentId, projectId);
    }

    /** 某仪器候补顺序。 */
    @GetMapping("/instruments/{instrumentId}/waitlist")
    public WaitlistView waitlist(@PathVariable String instrumentId) {
        return service.waitlist(instrumentId);
    }

    /** 全部候补接替事件。 */
    @GetMapping("/promotions")
    public List<PromotionView> promotions() {
        return service.promotions();
    }
}
