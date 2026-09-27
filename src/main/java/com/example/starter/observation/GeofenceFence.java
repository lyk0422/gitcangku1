package com.example.starter.observation;

/**
 * 围栏当前指针：对应 geofence_fence 表的一行。
 *
 * @param fenceId        围栏唯一标识
 * @param siteId         所属站点标识（创建后不可变）
 * @param currentVersion 当前生效配置版本号，从 1 开始
 */
public record GeofenceFence(
        String fenceId,
        String siteId,
        int currentVersion) {
}
