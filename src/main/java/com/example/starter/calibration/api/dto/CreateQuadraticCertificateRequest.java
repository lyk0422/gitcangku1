package com.example.starter.calibration.api.dto;

/**
 * 创建二次校准证书请求。声明 y = a·x² + b·x + c 及输入闭区间 [domainLo, domainHi]；
 * a 非零，各数值最多 6 位小数；导数须在整个区间严格同号（由端点精确验证）。
 * 时间须为带时区的 ISO-8601 字符串（按 UTC 归一）。
 *
 * @param requestId       全局幂等请求 ID（非空）
 * @param operator        操作者（非空）
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（含），ISO-8601
 * @param validTo         有效期终点（不含），ISO-8601
 * @param a               二次项系数 a（非零），十进制字符串
 * @param b               一次项系数 b，十进制字符串
 * @param c               常数项 c，十进制字符串
 * @param domainLo        输入闭区间下界 L（含），十进制字符串
 * @param domainHi        输入闭区间上界 U（含），十进制字符串
 * @param expectedVersion 期望版本（可选，参与幂等指纹比较）
 */
public record CreateQuadraticCertificateRequest(
        String requestId,
        String operator,
        String instrumentId,
        String validFrom,
        String validTo,
        String a,
        String b,
        String c,
        String domainLo,
        String domainHi,
        String expectedVersion) {
}
