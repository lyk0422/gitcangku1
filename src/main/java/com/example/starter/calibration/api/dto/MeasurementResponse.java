package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应（最新版本 + 重算链 + 放行历史）。
 * computedValue 为未舍入基础值；displayValue 为 HALF_UP 4 位；
 * compensationValue/compensatedValue 为环境补偿与补偿后值（未补偿为 null）；
 * usable 表示“当前可用”（最新版本已放行且证书未撤销）。
 *
 * @param id             最新版本行 ID
 * @param rootId         逻辑测量 ID（首版本行 ID）
 * @param versionNo      当前版本号
 * @param measurementKey 业务测量键
 * @param instrumentId   仪器 ID
 * @param instrumentModel 仪器型号；未记录环境为 null
 * @param measuredAt     测量时刻（UTC）
 * @param reading        原始读数（十进制字符串）
 * @param lowerLimit     合格下限（十进制字符串）
 * @param upperLimit     合格上限（十进制字符串）
 * @param temperatureC  环境温度（摄氏度，十进制字符串）；未记录为 null
 * @param humidityPct   环境湿度（%RH，十进制字符串）；未记录为 null
 * @param coefficientId  补偿系数版本 ID 快照；未补偿为 null
 * @param coefficientVersion 补偿系数版本号；未补偿为 null
 * @param certificateId  匹配到的证书 ID
 * @param computedValue  未舍入基础计算值（十进制字符串）
 * @param displayValue   基础值显示，HALF_UP 4 位（十进制字符串）
 * @param compensationValue 补偿值（6 位小数字符串）；未补偿为 null
 * @param compensatedValue 补偿后测量值（精确字符串）；未补偿为 null
 * @param uncertainty   扩展不确定度（十进制字符串）；未评估为 null
 * @param passed        基础值是否合格
 * @param passedAfterComp 补偿后是否超规格；未补偿为 null
 * @param status        状态：PENDING / RELEASED / REJECTED
 * @param usable        当前是否可用（最新版本已放行且证书未撤销）
 * @param rejectedBy    驳回人；未驳回为 null
 * @param rejectedAt    驳回时间（UTC）；未驳回为 null
 * @param rejectReason  驳回原因；未驳回为 null
 * @param createdAt      本版本创建时间（UTC）
 * @param chain        重算链全部版本（按版本号升序）
 * @param releases      最新版本放行历史
 */
public record MeasurementResponse(
        long id,
        long rootId,
        int versionNo,
        String measurementKey,
        String instrumentId,
        String instrumentModel,
        Instant measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String temperatureC,
        String humidityPct,
        Long coefficientId,
        Integer coefficientVersion,
        long certificateId,
        String computedValue,
        String displayValue,
        String compensationValue,
        String compensatedValue,
        String uncertainty,
        boolean passed,
        Boolean passedAfterComp,
        String status,
        boolean usable,
        String rejectedBy,
        Instant rejectedAt,
        String rejectReason,
        Instant createdAt,
        List<MeasurementVersionSummary> chain,
        List<ReleaseRecordResponse> releases) {
}
