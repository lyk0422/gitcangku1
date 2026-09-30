package com.example.starter.calibration;

import java.time.Instant;

import com.example.starter.calibration.service.BusinessClock;

/**
 * 测试用可控业务时钟：可随时设置当前时刻，验证系数版本生效区间的左闭右开边界。
 */
public class ControllableBusinessClock implements BusinessClock {

    private volatile Instant current;

    ControllableBusinessClock(Instant initial) {
        this.current = initial;
    }

    void setTime(Instant instant) {
        this.current = instant;
    }

    @Override
    public Instant now() {
        return current;
    }
}
