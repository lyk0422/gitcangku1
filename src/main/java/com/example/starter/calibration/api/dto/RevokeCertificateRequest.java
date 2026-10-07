package com.example.starter.calibration.api.dto;

/**
 * 撤销证书请求（请求体可选）。requestId 非空时启用幂等去重，此时 operator 必须非空。
 *
 * @param operator        操作者；requestId 非空时必填
 * @param requestId       全局幂等请求 ID；为空则不启用幂等
 * @param expectedVersion 期望版本，参与幂等指纹结构化比较
 */
public record RevokeCertificateRequest(
        String operator,
        String requestId,
        String expectedVersion) {
}
