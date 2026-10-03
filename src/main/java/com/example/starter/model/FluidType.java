package com.example.starter.model;

/**
 * 除冰液型，决定起飞有效期：A 为 20 分钟，B 为 35 分钟。
 */
public enum FluidType {

    /** 液型 A，起飞有效期 20 分钟。 */
    A(20),
    /** 液型 B，起飞有效期 35 分钟。 */
    B(35);

    private final int holdoverMinutes;

    FluidType(int holdoverMinutes) {
        this.holdoverMinutes = holdoverMinutes;
    }

    /**
     * 起飞有效期（分钟），完成时刻加该值为截止时刻（不含）。
     */
    public int holdoverMinutes() {
        return holdoverMinutes;
    }
}