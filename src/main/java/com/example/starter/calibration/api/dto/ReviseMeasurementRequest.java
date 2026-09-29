package com.example.starter.calibration.api.dto;

/**
 * 测量修订请求：以同一 measurementKey 提交完整后态，生成递增新版本；
 * expectedVersion 为客户端持有的当前最新版本，与服务端不一致返回 410（版本过期）。
 *
 * @param measurementKey  业务测量键（已存在，修订其版本链）
 * @param expectedVersion 客户端持有的当前最新版本号
 * @param instrumentId    仪器 ID
 * @param measuredAt      测量时刻，ISO-8601（按 UTC 归一）
 * @param reading         原始读数，十进制字符串
 * @param lowerLimit      合格下限（含端点）
 * @param upperLimit      合格上限（含端点）
 * @param submittedBy     提交人
 */
public record ReviseMeasurementRequest(
        String measurementKey,
        Integer expectedVersion,
        String instrumentId,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy) {
}
