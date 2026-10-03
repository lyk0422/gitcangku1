package com.example.starter.domain;

import java.time.Instant;

/**
 * 花序。
 *
 * @param id                     主键
 * @param code                   花序编号，全局唯一
 * @param firstOpenAt            首次开放时刻（UTC），授粉窗口起点
 * @param windowHours            有效窗口时长，单位小时
 * @param removedAt              摘除时刻（UTC）；null 表示未摘除
 * @param activeOperationId      当前有效授粉操作 ID；null 表示花序空闲可授粉
 * @param confirmedPollenBatchNo 确认成功后固化的花粉批号；null 表示尚无成功结果
 * @param confirmedAt            成功结果确认时刻（UTC）；null 表示未确认
 * @param createdAt              创建时刻（UTC）
 */
public record Inflorescence(long id, String code, Instant firstOpenAt, int windowHours,
                            Instant removedAt, Long activeOperationId,
                            String confirmedPollenBatchNo, Instant confirmedAt,
                            Instant createdAt) {

    /**
     * 当前有效授粉窗口：[firstOpenAt, firstOpenAt + windowHours)，右开区间。
     */
    public PollinationWindow window() {
        return new PollinationWindow(firstOpenAt, firstOpenAt.plusSeconds(windowHours * 3600L));
    }
}