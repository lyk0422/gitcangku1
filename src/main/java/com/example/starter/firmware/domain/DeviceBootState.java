package com.example.starter.firmware.domain;

/**
 * 设备启动分区状态。无记录时视为分区 A。
 *
 * @param deviceId         设备ID
 * @param currentPartition 当前启动分区：A 或 B
 */
public record DeviceBootState(String deviceId, String currentPartition) {

    public static final String DEFAULT_PARTITION = "A";

    /**
     * 另一分区：当前为 A 时返回 B，否则返回 A。
     */
    public static String otherPartition(String currentPartition) {
        return DEFAULT_PARTITION.equals(currentPartition) ? "B" : DEFAULT_PARTITION;
    }
}
