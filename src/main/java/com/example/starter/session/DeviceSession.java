package com.example.starter.session;

/**
 * 设备会话状态：对应 device_session 表的一行。
 *
 * @param deviceId        设备标识
 * @param sessionId       会话标识
 * @param watermark       确认水位：从 seq=1 起连续收到的最大序号，初值 0，洞不能跳过
 * @param closed          是否已关闭：true 后新消息一律 409
 * @param declaredLastSeq 关闭时设备声明的末序号；未关闭为 null
 */
public record DeviceSession(
        String deviceId,
        String sessionId,
        int watermark,
        boolean closed,
        Integer declaredLastSeq) {
}