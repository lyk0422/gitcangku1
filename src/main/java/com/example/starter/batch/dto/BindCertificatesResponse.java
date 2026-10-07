package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批量绑定响应。requestedBindingCount 为请求要求绑定数，boundBindingCount 为实际绑定数，
 * 成功时两者相等；任一失败整次回滚不返回本响应。
 */
public record BindCertificatesResponse(
        String batchKey,
        int requestedBindingCount,
        int boundBindingCount,
        List<BindingResponse> bindings
) {
}
