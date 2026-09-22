package com.example.starter.calibration.api.dto;

/**
 * 测量修订请求。仅原提交人可发起；仪器与测量时刻沿用第 1 版，不可修改。
 *
 * @param expectedRevision 客户端认为的当前最新版本号；与库内最新号不一致返回 409
 * @param requestId        修订幂等请求 ID；同键同参重放首次结果，改参 409
 * @param reason           非空修订原因
 * @param reading          修订后原始读数，十进制字符串（最多 6 位小数）
 * @param lowerLimit       修订后合格下限（含端点），十进制字符串
 * @param upperLimit       修订后合格上限（含端点），十进制字符串
 */
public record ReviseMeasurementRequest(
        Integer expectedRevision,
        String requestId,
        String reason,
        String reading,
        String lowerLimit,
        String upperLimit) {
}
