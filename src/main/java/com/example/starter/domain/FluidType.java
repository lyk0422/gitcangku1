package com.example.starter.domain;

import java.time.Duration;

/** 除冰液型及对应起飞有效期：A为20分钟，B为35分钟。 */
public enum FluidType {
    A(20),
    B(35);

    private final Duration validity;

    FluidType(int validityMinutes) {
        this.validity = Duration.ofMinutes(validityMinutes);
    }

    /** 起飞有效期，截止时刻 = 完成时刻 + 有效期，确认须严格早于截止时刻。 */
    public Duration validity() {
        return validity;
    }

    /** 按液型代码解析，非法代码抛出IllegalArgumentException。 */
    public static FluidType of(String code) {
        return FluidType.valueOf(code.trim().toUpperCase());
    }
}