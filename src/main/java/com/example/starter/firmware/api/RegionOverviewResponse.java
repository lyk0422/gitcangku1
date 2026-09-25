package com.example.starter.firmware.api;

import java.util.List;

/**
 * 区域限流总览：发布单各区域当前进行中任务数与等待设备数。
 *
 * @param releaseId   发布单ID
 * @param regionLimit 各区域同时进行中任务数上限，null 表示不限流
 * @param regions     区域明细，按区域标识字典序
 */
public record RegionOverviewResponse(long releaseId, Integer regionLimit, List<RegionOverview> regions) {

    /**
     * 单个区域的限流概览。
     *
     * @param region   区域标识
     * @param inFlight 当前进行中（已下发未完成）任务数
     * @param waiting  当前等待中的设备数
     */
    public record RegionOverview(String region, long inFlight, long waiting) {
    }
}
