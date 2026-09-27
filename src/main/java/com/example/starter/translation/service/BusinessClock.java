package com.example.starter.translation.service;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 业务时钟：冲突区间判定、评审与历史事件时间统一使用 UTC。
 * 默认读取系统时钟；测试可替换为固定时钟以验证区间边界与毫秒精度。
 */
@Component
public class BusinessClock {

    private volatile Instant fixed;

    /** 当前 UTC 时间；设置了固定时钟时返回固定时间，毫秒精度。 */
    public Instant now() {
        Instant value = fixed;
        Instant instant = value != null ? value : Instant.now();
        return Instant.ofEpochMilli(instant.toEpochMilli());
    }

    /** 测试用：固定时钟到给定 UTC 时间。 */
    public void setFixed(Instant instant) {
        this.fixed = instant == null ? null : Instant.ofEpochMilli(instant.toEpochMilli());
    }

    /** 测试用：恢复系统时钟。 */
    public void reset() {
        this.fixed = null;
    }
}
