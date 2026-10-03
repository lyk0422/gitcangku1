package com.example.starter.bridge.api;

import java.util.List;

import com.example.starter.bridge.ApplyResult;
import com.example.starter.bridge.BridgePermitService;
import com.example.starter.bridge.RejectReason;
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
 * 便桥通行调度接口：签发、进桥、出桥、撤销与查询。
 */
@RestController
@RequestMapping("/api/bridge")
public class BridgePermitController {

    private final BridgePermitService service;

    public BridgePermitController(BridgePermitService service) {
        this.service = service;
    }

    /**
     * 签发许可：核准返回 201（重放 200）；超重/时间倒退 422；窗口重叠或异参重放 409。
     */
    @PostMapping("/permits")
    public ResponseEntity<?> apply(@Valid @RequestBody ApplyRequest request) {
        ApplyResult result = service.apply(request.requestId(), request.plateNo(),
                request.massKg(), request.plannedEntryAt());
        if (result instanceof ApplyResult.Approved approved) {
            HttpStatus status = approved.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
            return ResponseEntity.status(status).body(PermitView.from(approved.permit()));
        }
        ApplyResult.Rejected rejected = (ApplyResult.Rejected) result;
        HttpStatus status = rejected.reason() == RejectReason.WINDOW_OVERLAP
                ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .body(new ErrorResponse(rejected.reason().name(), rejected.detail()));
    }

    /**
     * 确认进桥。
     */
    @PostMapping("/permits/{id}/enter")
    public PermitView enter(@PathVariable long id, @Valid @RequestBody OperationRequest request) {
        return PermitView.from(service.enter(id, request.requestId(), request.at()));
    }

    /**
     * 出桥释放。
     */
    @PostMapping("/permits/{id}/exit")
    public PermitView exit(@PathVariable long id, @Valid @RequestBody OperationRequest request) {
        return PermitView.from(service.exit(id, request.requestId(), request.at()));
    }

    /**
     * 撤销未进桥许可。
     */
    @PostMapping("/permits/{id}/cancel")
    public PermitView cancel(@PathVariable long id, @Valid @RequestBody CancelRequest request) {
        return PermitView.from(service.cancel(id, request.requestId()));
    }

    /**
     * 查询全部许可及状态。
     */
    @GetMapping("/permits")
    public List<PermitView> permits() {
        return service.listPermits().stream().map(PermitView::from).toList();
    }

    /**
     * 查询桥梁占用时间轴。
     */
    @GetMapping("/timeline")
    public TimelineView timeline() {
        return TimelineView.of(service.getConfig(), service.listPermits());
    }

    /**
     * 查询全部拒绝记录及拒绝依据。
     */
    @GetMapping("/rejections")
    public List<RejectionView> rejections() {
        return service.listRejections().stream().map(RejectionView::from).toList();
    }
}