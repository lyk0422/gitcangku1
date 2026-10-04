package com.example.starter.observation;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备会话序列确认 API：批次上传（水位推进）、会话显式关闭、水位状态查询。
 * 仅管理合成会话，不对接真实终端。
 */
@RestController
@RequestMapping("/api/device-sessions")
public class DeviceSessionController {

    private final DeviceSessionService deviceSessionService;

    public DeviceSessionController(DeviceSessionService deviceSessionService) {
        this.deviceSessionService = deviceSessionService;
    }

    /**
     * 上传观测批次：1 至 20 条有序消息，所有观测业务成功才整批保存并推进水位。
     */
    @PostMapping("/batches")
    public BatchResponse uploadBatch(@Valid @RequestBody UploadBatchRequest request) {
        return deviceSessionService.uploadBatch(request);
    }

    /**
     * 设备显式关闭会话：仅当当前水位等于声明末序号时成功。
     */
    @PostMapping("/close")
    public SessionStateView closeSession(@Valid @RequestBody CloseSessionRequest request) {
        return deviceSessionService.closeSession(request);
    }

    /**
     * 查询会话水位及水位以上已收到序号（升序）。
     */
    @GetMapping("/{deviceId}/{sessionId}")
    public SessionStateView getSession(@PathVariable String deviceId, @PathVariable String sessionId) {
        return deviceSessionService.getSession(deviceId, sessionId);
    }
}