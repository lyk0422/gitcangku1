package com.example.starter.calibration.api.dto;

/**
 * 测量修订请求。修订基于 expectedVersion 乐观并发：服务端当前版本不匹配时返回 410。
 * 修订后重新匹配测量时刻的有效证书并重新固化计算值与合格判定；测量版本号自增。
 *
 * @param measuredAt      修订后测量时刻，ISO-8601（按 UTC 归一）
 * @param reading         修订后原始读数，十进制字符串
 * @param lowerLimit      修订后合格下限（含端点）
 * @param upperLimit      修订后合格上限（含端点）
 * @param expectedVersion 客户端读取到的测量版本号；与当前版本不一致返回 410
 */
public record ReviseMeasurementRequest(
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        Long expectedVersion) {
}
