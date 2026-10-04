package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批量绑定响应：requestedCount 为请求条数（要求值），boundCount 为实际绑定条数（实际值），
 * 全部成功时二者相等。
 */
public record BatchBindResponse(
        int requestedCount,
        int boundCount,
        List<BindingResponse> bindings
) {
}