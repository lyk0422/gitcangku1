package com.example.starter.certificate.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量绑定响应：requestedCount 为请求项数（要求值），boundCount 为实际绑定数（实际值）；
 * bindings 按 batchKey 升序（稳定排序）。
 */
public record BatchBindResponse(
        int requestedCount,
        int boundCount,
        List<BindingResponse> bindings,
        Instant boundAt
) {
}