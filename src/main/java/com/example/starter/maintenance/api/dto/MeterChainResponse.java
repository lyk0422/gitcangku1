package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 工时表更换链完整快照：更换登记的成功响应，也是只读链查询与重算版本查询的视图。
 *
 * @param equipmentId       设备唯一标识
 * @param equipmentVersion  设备版本号（更换响应中为更换后的版本）
 * @param recalcVersion     链式重算版本号：已关闭表最后有效读数被修订触发全链重算的次数
 * @param meters            链内全部工时表（按 chainSeq 升序）
 * @param replacements      全部更换记录（按登记时刻升序）
 */
public record MeterChainResponse(
        String equipmentId,
        long equipmentVersion,
        long recalcVersion,
        List<MeterView> meters,
        List<ReplacementView> replacements) {
}
