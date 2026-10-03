package com.example.starter.bridge;

/**
 * 便桥调度配置，对应 bridge_state 单例行。
 */
public record BridgeState(
        /** 主键，固定为 1。 */
        int id,
        /** 便桥名称。 */
        String bridgeName,
        /** 桥梁允许的最大车辆总质量，单位千克。 */
        int maxTotalMassKg,
        /** 单次通行预计时长，单位分钟。 */
        int crossingMinutes) {
}