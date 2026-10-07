package com.example.starter.calibration.api.dto;

/**
 * 创建校准证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一），a/b/c 为最多 6 位小数的十进制字符串。
 * mode 缺省 LINEAR（y=ax+b）；QUADRATIC（y=ax²+bx+c）时 a 非零且须给出输入闭区间 [domainLo, domainHi]，
 * 导数在区间内严格同号（非单调 400）。
 * requestId 非空时启用幂等去重，此时 operator 必须非空。
 *
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（含），ISO-8601
 * @param validTo         有效期终点（不含），ISO-8601
 * @param mode            证书模式：LINEAR / QUADRATIC，缺省 LINEAR
 * @param a               校准系数 a，十进制字符串
 * @param b               校准偏移 b，十进制字符串
 * @param c               二次常数项 c（仅 QUADRATIC），十进制字符串
 * @param domainLo        二次输入闭区间下界 L（仅 QUADRATIC），十进制字符串
 * @param domainHi        二次输入闭区间上界 U（仅 QUADRATIC），十进制字符串
 * @param operator        操作者；requestId 非空时必填
 * @param requestId       全局幂等请求 ID；为空则不启用幂等
 * @param expectedVersion 期望版本，参与幂等指纹结构化比较
 */
public record CreateCertificateRequest(
        String instrumentId,
        String validFrom,
        String validTo,
        String mode,
        String a,
        String b,
        String c,
        String domainLo,
        String domainHi,
        String operator,
        String requestId,
        String expectedVersion) {
}
