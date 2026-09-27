package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 保养周期。周期区间左闭右开：起点等于上次保养锚点时刻，下一周期锚点必须严格晚于该起点。
 *
 * @param cycleId                       周期主键
 * @param equipmentId                   所属设备标识
 * @param cycleNo                       周期号，设备内从 1 递增
 * @param periodMinutes                 周期要求工时（分钟），创建时由设备快照固化
 * @param open                          是否进行中；false 表示已完工（COMPLETED），不可重开
 * @param startAnchorSampledAt          起点锚点时刻（UTC）；首周期为 null
 * @param startAnchorCumulativeMinutes  起点锚点工时（分钟）；首周期为 0
 * @param currentEvidenceVersion        当前周期最新证据包版本；0 表示尚无证据包
 * @param currentTemplateVersion        最新证据包固化的模板版本；无证据包时为 null
 * @param completedAt                   完工时刻（UTC）；未完工为 null
 * @param endAnchorSampledAt            完工锚点采样时刻（UTC 快照）；未完工为 null
 * @param endAnchorCumulativeMinutes    完工锚点工时快照（分钟）；未完工为 null
 * @param maintenanceId                 完工生成的保养记录标识；未完工为 null
 */
public record MaintenanceCycle(
        long cycleId,
        String equipmentId,
        int cycleNo,
        long periodMinutes,
        boolean open,
        Instant startAnchorSampledAt,
        long startAnchorCumulativeMinutes,
        int currentEvidenceVersion,
        Integer currentTemplateVersion,
        Instant completedAt,
        Instant endAnchorSampledAt,
        Long endAnchorCumulativeMinutes,
        Long maintenanceId) {
}
