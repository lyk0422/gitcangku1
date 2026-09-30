package com.example.starter.evidence;

/**
 * 巡检清单中每件证物的处置/隔离状态。
 * QUARANTINED 异常巡检确认时在箱，已隔离待复核；
 * RELEASED 复核放行后恢复 SEALED（清单行保留，仅更新处置状态与放行时刻）；
 * NOT_PRESENT 巡检时证物借出未还、物理不在箱内，不隔离；
 * UNQUARANTINED 封条完好巡检，无需隔离。
 */
public enum ItemDisposition {
    QUARANTINED,
    RELEASED,
    NOT_PRESENT,
    UNQUARANTINED
}
