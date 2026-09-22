package com.example.starter.baggage.api.dto;

import java.util.List;

/**
 * 行李轨迹视图。
 *
 * @param bagTag         行李牌号
 * @param status         状态：IN_TRANSIT / DELIVERED
 * @param currentStation 当前所在站
 * @param nextLegIndex   待乘航段索引
 * @param loadedLegId    当前已装载航段，未装载为 null
 * @param segments       行程各段及其状态
 */
public record TraceView(String bagTag, String status, String currentStation,
                        int nextLegIndex, String loadedLegId, List<Segment> segments) {

    /**
     * 行程单段。
     *
     * @param seq         行程顺序号（0 起）
     * @param legId       航段标识
     * @param origin      始发站
     * @param destination 到达站
     * @param state       段状态：COMPLETED 已完成 / CURRENT 待乘或装载中 / PENDING 未开始
     */
    public record Segment(int seq, String legId, String origin, String destination, String state) {
    }
}
