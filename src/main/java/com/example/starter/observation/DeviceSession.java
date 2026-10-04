package com.example.starter.observation;

/**
 * 设备会话状态：对应 device_session 表的一行。
 *
 * @param deviceId     设备标识
 * @param sessionId    会话标识
 * @param watermark    确认水位：从 seq=1 起连续收到的最大序号，初值 0，洞不能跳过
 * @param messageCount 已收件消息条数，上限 100
 * @param closed       是否已由设备显式关闭：true 后拒绝新消息
 * @param closedSeq    关闭时设备声明的末序号（须等于当时水位）；未关闭为 null
 */
public record DeviceSession(
        String deviceId,
        String sessionId,
        int watermark,
        int messageCount,
        boolean closed,
        Integer closedSeq) {
}