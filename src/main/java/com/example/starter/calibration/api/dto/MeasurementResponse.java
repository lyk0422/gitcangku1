package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * usable 表示“当前可用”（已放行且证书未撤销）。
 *
 * @param id             测量记录 ID
 * @param measurementKey 业务测量键
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻（UTC）
 * @param reading        原始读数（十进制字符串）
 * @param lowerLimit     合格下限（十进制字符串）
 * @param upperLimit     合格上限（十进制字符串）
 * @param submittedBy    提交人
 * @param certificateId  匹配到的证书 ID
 * @param computedValue  未舍入计算值（十进制字符串）
 * @param displayValue   显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed         是否合格（基于未舍入值，含端点）
 * @param status         状态：PENDING / RELEASED
 * @param usable         当前是否可用（已放行且证书未撤销）
 * @param version        测量版本号；修订自增
 * @param budget         当前不确定度预算视图（budgetVersion=0 表示尚未登记分量）
 * @param revisions      测量修订历史（按修订先后升序）
 * @param createdAt      提交时间（UTC）
 * @param releases       放行历史
 */
public record MeasurementResponse(
        long id,
        String measurementKey,
        String instrumentId,
        Instant measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        long certificateId,
        String computedValue,
        String displayValue,
        boolean passed,
        String status,
        boolean usable,
        long version,
        BudgetView budget,
        List<RevisionResponse> revisions,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {

    /**
     * 当前不确定度预算视图。
     *
     * @param budgetVersion       预算版本号；0 表示尚未登记任何分量
     * @param components          当前分量，名称升序
     * @param combinedUncertainty 合成不确定度（RSS，HALF_UP 4 位）
     * @param calculationMethod   合成口径标识
     */
    public record BudgetView(
            long budgetVersion,
            List<UncertaintyComponentResponse> components,
            String combinedUncertainty,
            String calculationMethod) {
    }
}
