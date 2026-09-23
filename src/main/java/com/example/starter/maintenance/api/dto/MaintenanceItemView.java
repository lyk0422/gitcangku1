package com.example.starter.maintenance.api.dto;

/**
 * 受影响保养项目视图：修正前实际状态与修正后（投影）状态。
 *
 * @param itemKey                     保养项目标识（当前模型每设备一个主保养项目 MAIN）
 * @param periodMinutes               保养周期（分钟）
 * @param runMillisBefore             修正前本轮运行时长（毫秒）
 * @param statusBefore                修正前保养状态（DUE/NOT_DUE）
 * @param nextThresholdMillisBefore   修正前下一阈值（毫秒）
 * @param runMillisAfter              修正后本轮运行时长（毫秒）
 * @param statusAfter                 修正后保养状态（DUE/NOT_DUE）
 * @param nextThresholdMillisAfter    修正后下一阈值（毫秒）= 最近保养锚点工时 + 保养周期
 */
public record MaintenanceItemView(
        String itemKey,
        long periodMinutes,
        long runMillisBefore,
        String statusBefore,
        long nextThresholdMillisBefore,
        long runMillisAfter,
        String statusAfter,
        long nextThresholdMillisAfter) {
}
