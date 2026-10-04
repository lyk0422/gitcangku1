package com.example.starter.session;

import java.util.List;

/**
 * 上传批次响应：整批保存后的会话水位与逐条回执；同 requestId 重放返回首次完整响应。
 *
 * @param deviceId                设备标识
 * @param sessionId               会话标识
 * @param watermark               批次提交后的确认水位
 * @param receivedAboveWatermark  水位以上已收到的序号，按升序排列
 * @param receipts                逐条消息回执，顺序与请求消息一致
 */
public record UploadBatchResponse(
        String deviceId,
        String sessionId,
        int watermark,
        List<Integer> receivedAboveWatermark,
        List<MessageReceipt> receipts) {
}