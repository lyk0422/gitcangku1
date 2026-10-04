package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备会话序列确认水位 API：批次上传、会话状态查询、设备显式关闭。仅管理合成会话。
 */
@RestController
@RequestMapping("/api/sessions")
@Validated
public class DeviceSessionController {

    private final DeviceSessionService sessionService;

    public DeviceSessionController(DeviceSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 上传批次：1 至 20 条有序消息，全部观测业务成功才整批保存。
     */
    @PostMapping("/batches")
    public ResponseEntity<BatchUploadResponse> uploadBatch(@Valid @RequestBody BatchUploadRequest request) {
        DeviceSessionService.BatchOutcome outcome = sessionService.uploadBatch(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询会话状态：水位及水位以上已收到序号（升序）。
     */
    @GetMapping("/{deviceId}/{sessionId}")
    public SessionStateResponse getSession(@PathVariable String deviceId, @PathVariable String sessionId) {
        return sessionService.getSession(deviceId, sessionId);
    }

    /**
     * 设备显式关闭会话：仅当水位等于声明末序号时成功。
     */
    @PostMapping("/{deviceId}/{sessionId}/close")
    public ResponseEntity<SessionStateResponse> closeSession(@PathVariable String deviceId,
                                                             @PathVariable String sessionId,
                                                             @Valid @RequestBody CloseSessionRequest request) {
        DeviceSessionService.CloseOutcome outcome = sessionService.closeSession(deviceId, sessionId, request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}