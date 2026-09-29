package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量绑定响应：按请求顺序返回每条已落定的绑定明细；全部条目在同一事务提交。
 * boundAt 为本次绑定的 UTC 时点。
 */
public record BindCertificatesResponse(
        List<CertificateBindingResponse> bindings,
        int boundCount,
        Instant boundAt
) {
}
