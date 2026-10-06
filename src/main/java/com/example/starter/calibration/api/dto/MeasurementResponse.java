package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 测量明细响应。computedValue 为未舍入精确值；quantizedValue/gridK/resolutionVersion 为提交时
 * 冻结的格点量化结果（未配置仪器或越界待审时为 null）；displayValue 为 HALF_UP 保留 4 位的显示值，
 * 不参与任何判定；usable 表示“当前可用”（已放行且证书未撤销）。
 *
 * @param id                测量记录 ID
 * @param measurementKey    业务测量键
 * @param instrumentId      仪器 ID
 * @param measuredAt        测量时刻（UTC）
 * @param reading           原始读数（十进制字符串）
 * @param lowerLimit        合格下限（十进制字符串）
 * @param upperLimit        合格上限（十进制字符串）
 * @param submittedBy       提交人
 * @param certificateId     匹配到的证书 ID
 * @param computedValue     未舍入精确计算值（十进制字符串）；越界待审时为 null
 * @param displayValue      显示值，HALF_UP 4 位小数（十进制字符串）；越界待审时为 null
 * @param quantizedValue    冻结的量化值 o+k×s（十进制字符串）；未量化时为 null
 * @param gridK             冻结的格点整数 k（十进制字符串）；未量化时为 null
 * @param resolutionVersion 冻结的分辨率配置版本；未配置仪器时为 null
 * @param passed            是否合格（已配置仪器需精确值与量化值均在闭区间）
 * @param status            状态：PENDING / RELEASED
 * @param usable            当前是否可用（已放行且证书未撤销）
 * @param createdAt         提交时间（UTC）
 * @param releases          放行历史
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
        String quantizedValue,
        String gridK,
        Integer resolutionVersion,
        boolean passed,
        String status,
        boolean usable,
        Instant createdAt,
        List<ReleaseRecordResponse> releases) {
}
