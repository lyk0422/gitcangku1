package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 创建校准证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一）。
 * 线性证书提供 a/b；曲线证书提供 3～8 个节点（原始值严格递增），两者互斥。
 *
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（含），ISO-8601
 * @param validTo         有效期终点（不含），ISO-8601
 * @param a               校准系数 a，十进制字符串（线性证书必填）
 * @param b               校准偏移 b，十进制字符串（线性证书必填）
 * @param nodes           曲线节点列表；非空时创建曲线证书，且不得同时提供 a/b
 * @param requestId       幂等请求键；提供时启用去重，同键同参重放首次响应，异参 409
 * @param expectedVersion 期望版本，仅作为幂等身份的一部分参与结构化比较
 */
public record CreateCertificateRequest(
        String instrumentId,
        String validFrom,
        String validTo,
        String a,
        String b,
        List<NodeDto> nodes,
        String requestId,
        Long expectedVersion) {
}
