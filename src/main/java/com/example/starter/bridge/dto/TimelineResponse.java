package com.example.starter.bridge.dto;

import java.time.Instant;
import java.util.List;

/**
 * 桥梁占用时间轴视图；occupiedTo 为空表示占用持续中（已进桥未出桥）。
 */
public record TimelineResponse(
        /** 桥梁允许的最大车辆总质量，单位千克。 */
        int maxTotalMassKg,
        /** 单次通行预计时长，单位分钟。 */
        int crossingMinutes,
        /** 时间轴生成时刻，UTC。 */
        Instant generatedAt,
        /** 许可占用条目，按窗口起点升序。 */
        List<Entry> entries,
        /** 拒绝记录，按创建时刻升序。 */
        List<Rejection> rejections) {

    /**
     * 单条许可占用；占用区间 [occupiedFrom, occupiedTo)，occupiedTo 为空表示持续占用。
     */
    public record Entry(
            /** 许可主键。 */
            long permitId,
            /** 车牌代号。 */
            String plateNo,
            /** 许可状态。 */
            String status,
            /** 许可窗口起点（含），UTC。 */
            Instant windowStartAt,
            /** 许可窗口终点（不含），UTC。 */
            Instant windowEndAt,
            /** 实际进桥时刻，UTC；未进桥为空。 */
            Instant actualEntryAt,
            /** 实际出桥时刻，UTC；未出桥为空。 */
            Instant actualExitAt,
            /** 占用起点，UTC；已撤销许可为空。 */
            Instant occupiedFrom,
            /** 占用终点（不含），UTC；已撤销为空，已进桥未出桥为空表示持续占用。 */
            Instant occupiedTo) {
    }

    /**
     * 单条拒绝记录。
     */
    public record Rejection(
            /** 被拒绝申请的请求键。 */
            String requestId,
            /** 车牌代号。 */
            String plateNo,
            /** 拒绝原因代码。 */
            String reasonCode,
            /** 拒绝原因说明。 */
            String reasonDetail,
            /** 拒绝发生时刻，UTC。 */
            Instant createdAt) {
    }
}