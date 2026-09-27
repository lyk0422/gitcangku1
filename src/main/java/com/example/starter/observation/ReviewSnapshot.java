package com.example.starter.observation;

import java.time.Instant;

/**
 * 许可撤销复核快照：撤销许可时对该许可下尚未合并（RECEIVED）的观测写入，内容不可变。
 *
 * @param permitId         被撤销的许可标识
 * @param permitVersion    观测接收时命中的许可版本
 * @param revokedAtUtc     许可撤销完成时刻（UTC）
 * @param reason           撤销时填写的原因
 * @param deviceId         采样设备标识
 * @param gridId           采集网格单元编号
 * @param observedAtUtc    采集时刻（UTC）
 * @param previousStatus   撤销前观测业务状态（固定 RECEIVED）
 */
public record ReviewSnapshot(
        String permitId,
        String permitVersion,
        Instant revokedAtUtc,
        String reason,
        String deviceId,
        int gridId,
        Instant observedAtUtc,
        ObservationStatus previousStatus) {
}
