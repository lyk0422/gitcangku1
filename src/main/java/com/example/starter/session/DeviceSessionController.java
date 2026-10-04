package com.example.starter.session;

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
 * 设备会话序列确认水位 API：消息批次上传、会话状态查询、显式关闭。仅管理合成会话。
 */
@RestController
@RequestMapping("/api/device-sessions")
@Validated
public class DeviceSessionController {

    private final DeviceSessionService sessionService;

    public DeviceSessionController(DeviceSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 上传消息批次：全部观测业务成功才整批保存，返回批次提交后的水位与逐条回执。
     */
    @PostMapping("/batches")
    public ResponseEntity<UploadBatchResponse> uploadBatch(@Valid @RequestBody UploadBatchRequest request) {
        return ResponseEntity.ok(sessionService.uploadBatch(request));
    }

    /**
     * 查询会话状态：返回水位及水位以上已收到序号（升序）。
     */
    @GetMapping("/{deviceId}/{sessionId}")
    public SessionStateResponse getSession(@PathVariable String deviceId, @PathVariable String sessionId) {
        return sessionService.getSession(deviceId, sessionId);
    }

    /**
     * 设备显式关闭会话：仅当水位等于声明末序号时成功。
     */
    @PostMapping("/{deviceId}/{sessionId}/close")
    public SessionStateResponse closeSession(@PathVariable String deviceId, @PathVariable String sessionId,
                                             @Valid @RequestBody CloseSessionRequest request) {
        return sessionService.closeSession(deviceId, sessionId, request);
    }
}