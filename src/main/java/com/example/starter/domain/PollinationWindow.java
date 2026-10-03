package com.example.starter.domain;

import java.time.Instant;

/**
 * 授粉有效窗口，左闭右开区间 [openAt, closeAt)。
 * 例如 10:00 开放、24 小时窗口：次日 09:59 可授粉，次日 10:00 起拒绝。
 *
 * @param openAt  窗口起点（UTC，含）
 * @param closeAt 窗口终点（UTC，不含）
 */
public record PollinationWindow(Instant openAt, Instant closeAt) {

    /**
     * 判断给定时刻是否落在窗口内（含起点、不含终点）。
     */
    public boolean contains(Instant moment) {
        return !moment.isBefore(openAt) && moment.isBefore(closeAt);
    }

    /**
     * 判断给定时刻是否已过窗口终点。
     */
    public boolean isExpiredAt(Instant moment) {
        return !moment.isBefore(closeAt);
    }
}