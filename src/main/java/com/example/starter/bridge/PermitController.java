package com.example.starter.bridge;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.bridge.dto.ApplyPermitRequest;
import com.example.starter.bridge.dto.PermitOperationRequest;
import com.example.starter.bridge.dto.PermitResponse;
import com.example.starter.bridge.dto.TimelineResponse;

/**
 * 便桥许可调度 REST 入口；合成调度规则，不接入真实交通控制。
 */
@RestController
@RequestMapping("/api/bridge")
public class PermitController {

    private final PermitService permitService;

    public PermitController(PermitService permitService) {
        this.permitService = permitService;
    }

    /** 签发许可：201 新建；同 requestId 同参重放返回 200 与原许可。 */
    @PostMapping("/permits")
    public ResponseEntity<PermitResponse> apply(@Valid @RequestBody ApplyPermitRequest request) {
        boolean replay = permitService.findByRequestId(request.requestId()).isPresent();
        BridgePermit permit = permitService.apply(request);
        HttpStatus status = replay ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(PermitResponse.from(permit));
    }

    /** 进桥确认：仅许可窗口内可进桥。 */
    @PostMapping("/permits/{permitId}/entry")
    public PermitResponse confirmEntry(@PathVariable long permitId,
            @Valid @RequestBody PermitOperationRequest request) {
        return PermitResponse.from(permitService.confirmEntry(permitId, request.requestId()));
    }

    /** 出桥释放：记录实际出桥时刻。 */
    @PostMapping("/permits/{permitId}/exit")
    public PermitResponse confirmExit(@PathVariable long permitId,
            @Valid @RequestBody PermitOperationRequest request) {
        return PermitResponse.from(permitService.confirmExit(permitId, request.requestId()));
    }

    /** 撤销许可：仅未进桥许可可撤销。 */
    @PostMapping("/permits/{permitId}/cancel")
    public PermitResponse cancel(@PathVariable long permitId,
            @Valid @RequestBody PermitOperationRequest request) {
        return PermitResponse.from(permitService.cancel(permitId, request.requestId()));
    }

    /** 查询单个许可状态。 */
    @GetMapping("/permits/{permitId}")
    public PermitResponse getPermit(@PathVariable long permitId) {
        return PermitResponse.from(permitService.getPermit(permitId));
    }

    /** 查询桥梁占用时间轴、许可状态与拒绝依据。 */
    @GetMapping("/timeline")
    public TimelineResponse timeline() {
        return permitService.timeline();
    }
}