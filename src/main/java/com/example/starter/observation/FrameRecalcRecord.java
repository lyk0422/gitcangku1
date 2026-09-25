package com.example.starter.observation;

import java.util.List;

/**
 * 不可变基准重算记录：对应 frame_recalc 表的一行。
 * 重算改变簇成员或当前胜出记录时与重算同事务原子写入，固化新旧簇和参数版本，永不修改。
 *
 * @param recalcId         重算记录唯一标识（取触发请求的 requestId）
 * @param deviceId         被重算的设备标识
 * @param requestId        触发重算的请求标识
 * @param oldFrameVersion  重算前设备基准版本（参数版本）；首次登记时为 null
 * @param newFrameVersion  重算后设备基准版本（参数版本）
 * @param oldClusters      重算前设备相关簇快照
 * @param newClusters      重算后设备相关簇快照
 */
public record FrameRecalcRecord(
        String recalcId,
        String deviceId,
        String requestId,
        String oldFrameVersion,
        String newFrameVersion,
        List<ClusterSnapshot> oldClusters,
        List<ClusterSnapshot> newClusters) {
}
