package com.example.starter.observation;

import java.util.List;

/**
 * 设备会话序列确认状态：水位及水位以上已收到序号（升序）。
 *
 * @param deviceId    设备标识
 * @param sessionId   会话标识
 * @param watermark   确认水位：从 seq=1 起连续收到的最大序号，初值 0
 * @param pendingSeqs 水位以上已收到的序号，升序排列
 * @param closed      会话是否已由设备显式关闭
 */
public record SessionStateView(
        String deviceId,
        String sessionId,
        int watermark,
        List<Integer> pendingSeqs,
        boolean closed) {
}