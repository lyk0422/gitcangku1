package com.example.starter.session;

import java.util.List;

/**
 * 会话状态查询/关闭响应：返回当前水位及水位以上已收到序号（升序）。
 *
 * @param deviceId                设备标识
 * @param sessionId               会话标识
 * @param watermark               确认水位，初值 0
 * @param closed                  是否已关闭
 * @param receivedAboveWatermark  水位以上已收到的序号，按升序排列
 */
public record SessionStateResponse(
        String deviceId,
        String sessionId,
        int watermark,
        boolean closed,
        List<Integer> receivedAboveWatermark) {
}