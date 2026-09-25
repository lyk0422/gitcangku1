package com.example.starter.firmware.domain;

import java.time.LocalDateTime;

/**
 * 区域限流等待记录，设备成功领取任务后删除。
 *
 * @param releaseId 所属发布单ID
 * @param deviceId  被限流的设备ID
 * @param region    设备所属区域标识（冗余自设备表，便于按区域查询）
 * @param waitedAt  最近一次被限流时刻（服务器本地时区），公平排队按此时刻升序、同刻按设备ID字典序
 */
public record RegionWaitRecord(long releaseId, String deviceId, String region, LocalDateTime waitedAt) {
}
