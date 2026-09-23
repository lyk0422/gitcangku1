package com.example.starter.maintenance.domain;

/**
 * 工时表：设备更换链中的一个环节。任意读数虚拟工时 virtual = raw + offsetHours；
 * 链式连续性以各表最后有效读数为准：offset(后继) = virtual(前驱最后有效读数) - initialRawHours(后继)。
 *
 * @param meterKey         工时表唯一标识（全局唯一，保证更换链不可成环）
 * @param equipmentId      所属设备标识
 * @param chainSeq         链内序号，初始表为 0，每次更换加一
 * @param status           表状态：ACTIVE=当前可写表（每设备仅一张），CLOSED=已更换关闭
 * @param initialRawHours  新表起始原始读数（分钟），非负；该表读数不得小于此值
 * @param finalRawHours    关闭时申报的最终原始读数（分钟）；NULL=仍 ACTIVE；关闭后修订不得超过此值
 * @param offsetHours      虚拟工时偏移（分钟）：更换时冻结，仅随前驱最后有效读数修订触发的重算而更新；可为负
 */
public record Meter(
        String meterKey,
        String equipmentId,
        int chainSeq,
        String status,
        long initialRawHours,
        Long finalRawHours,
        long offsetHours) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CLOSED = "CLOSED";

    /** 该表基准虚拟工时：raw = initialRawHours 时映射到的虚拟工时。 */
    public long baseVirtualHours() {
        return initialRawHours + offsetHours;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
