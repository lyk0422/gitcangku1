package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 创建校准证书请求。时间须为带时区的 ISO-8601 字符串（按 UTC 归一）。
 * LINEAR（默认）：a/b 为最多 6 位小数的十进制字符串，nodes 必须为空。
 * CURVE：nodes 为 3～8 个节点，原始值严格递增，均最多 6 位小数，a/b 必须为空。
 *
 * @param instrumentId    仪器 ID
 * @param validFrom       有效期起点（含），ISO-8601
 * @param validTo         有效期终点（不含），ISO-8601
 * @param type            证书类型：LINEAR / CURVE；缺省为 LINEAR
 * @param a               线性校准系数 a，十进制字符串；CURVE 必须为 null
 * @param b               线性校准偏移 b，十进制字符串；CURVE 必须为 null
 * @param nodes           曲线节点列表；LINEAR 必须为 null 或空
 * @param requestId       幂等请求 ID；可选，缺省时不做去重
 * @param expectedVersion 期望版本；可选，仅作为幂等身份的一部分参与结构化比较
 */
public record CreateCertificateRequest(
        String instrumentId,
        String validFrom,
        String validTo,
        String type,
        String a,
        String b,
        List<NodeRequest> nodes,
        String requestId,
        String expectedVersion) {

    /**
     * 曲线节点请求。raw 与 corrected 均为最多 6 位小数的十进制字符串。
     *
     * @param raw       节点原始值
     * @param corrected 节点校正值
     */
    public record NodeRequest(String raw, String corrected) {
    }
}
