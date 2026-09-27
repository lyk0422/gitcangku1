package com.example.starter.maintenance.domain;

/**
 * 保养状态判定（纯函数，UTC 口径，不依赖时钟）。
 * 本轮运行分钟区间左闭右开：未达周期为 OK；达到周期（含边界）未达逾期阈值为 DUE；
 * 达到逾期阈值（含边界）为 OVERDUE。未配置逾期阈值（null）时永不判定 OVERDUE。
 */
public final class MaintenanceStatus {

    /** 状态：OK / DUE / OVERDUE。 */
    public static String evaluate(long runMinutes, long periodMinutes, Long overdueThresholdMinutes) {
        if (overdueThresholdMinutes != null && runMinutes >= overdueThresholdMinutes) {
            return "OVERDUE";
        }
        if (runMinutes >= periodMinutes) {
            return "DUE";
        }
        return "OK";
    }

    /** 距保养周期的差额（要求值 - 实际值）；已达到周期时为 0 或负。 */
    public static long remainingMinutes(long runMinutes, long periodMinutes) {
        return periodMinutes - runMinutes;
    }

    /** 超出逾期阈值的差额（实际值 - 阈值）；未配置阈值或未达阈值时为 0。 */
    public static long overdueByMinutes(long runMinutes, Long overdueThresholdMinutes) {
        if (overdueThresholdMinutes == null || runMinutes < overdueThresholdMinutes) {
            return 0L;
        }
        return runMinutes - overdueThresholdMinutes;
    }

    private MaintenanceStatus() {
    }
}
