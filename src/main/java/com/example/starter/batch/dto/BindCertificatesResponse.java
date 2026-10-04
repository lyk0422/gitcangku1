package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批量绑定响应。requestedCount 为请求条数（要求值），boundCount 为实际绑定条数（实际值）；
 * 任一失败整次回滚，成功时两者相等。
 */
public record BindCertificatesResponse(
        int requestedCount,
        int boundCount,
        List<BindingResponse> bindings
) {
}