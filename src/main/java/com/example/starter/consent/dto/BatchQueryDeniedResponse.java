package com.example.starter.consent.dto;

import java.util.List;

/**
 * 批次查询阻断响应：任一主体校验失败返回 403，稳定列出各主体原因且不返回任何数据。
 *
 * @param code    稳定业务码，固定为 BATCH_QUERY_DENIED
 * @param message 可读描述
 * @param reasons 各主体阻断原因，按主体标识排序
 */
public record BatchQueryDeniedResponse(
        String code,
        String message,
        List<SubjectDenial> reasons) {
}
