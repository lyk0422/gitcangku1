package com.example.starter.firmware.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 区域等待清单：按最近一次限流时刻升序、同时刻按设备标识字典序。
 */
public record WaitingListResponse(List<WaitingEntry> waiting) {

    /**
     * 等待中的设备。
     *
     * @param deviceId        设备ID
     * @param lastThrottledAt 最近一次被限流时刻（服务器本地时区）
     */
    public record WaitingEntry(String deviceId, LocalDateTime lastThrottledAt) {
    }
}
