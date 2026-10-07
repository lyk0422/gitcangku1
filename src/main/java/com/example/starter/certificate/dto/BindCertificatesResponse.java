package com.example.starter.certificate.dto;

import java.util.List;

/**
 * 批量绑定响应：boundCount 为实际绑定条数，bindings 按请求顺序返回。
 */
public record BindCertificatesResponse(
        int boundCount,
        List<BindingResponse> bindings
) {
}
