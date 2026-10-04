package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 会话状态响应：确认水位及水位以上已收到序号（升序）。
 *
 * @param deviceId     设备标识
 * @param sessionId    会话标识
 * @param watermark    确认水位：从 seq=1 起连续收到的最大序号，初值 0
 * @param pending      水位以上已收到的序号，按升序排列
 * @param messageCount 已收件消息条数（上限 100）
 * @param closed       是否已由设备显式关闭
 * @param finalSeq     关闭时声明的末序号；未关闭为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionStateResponse(
        String deviceId,
        String sessionId,
        int watermark,
        List<Integer> pending,
        int messageCount,
        boolean closed,
        Integer finalSeq) {
}