package com.example.starter.calibration.service;

import java.time.Instant;

/**
 * 业务时钟：系数版本生效区间的判定以此为“当前时刻”。
 * 生产环境使用系统 UTC 时钟；测试可注入固定/可控时钟验证左闭右开边界。
 */
public interface BusinessClock {

    /** 当前时刻（UTC）。 */
    Instant now();
}
