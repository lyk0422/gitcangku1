package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 测量明细响应。computedValue 为未舍入精确值；displayValue 为 HALF_UP 保留 4 位的显示值；
 * usable 表示“当前可用”（已放行且证书未撤销）。
 * 历史查询始终返回原结果与放行历史；受失效影响时显式输出 impactVersion 与冻结血缘路径。
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
 * @param status         状态：PENDING / RELEASED / BLOCKED / REVIEW_REQUIRED
 * @param usable         当前是否可用（已放行且证书未撤销）
 * @param standardId     提交时绑定的标准器版本业务键；未绑定为 null 不输出
 * @param impactVersion  失效影响版本号；未受影响为 null 不输出
 * @param impactPath     冻结的到失效根最短血缘路径；未受影响为 null 不输出
 * @param createdAt      提交时间（UTC）
 * @param releases       放行历史（失效后保留原放行快照）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
        String standardId,
        String impactVersion,
        String impactPath,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
