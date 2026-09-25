package com.example.starter.batch;

import java.util.List;

/**
 * 条件核销 422 错误响应体：在统一错误码/信息之外，附带机器可读的阻止原因
 * （CONDITION_EXPIRED 到期降级 / BATCH_RECALLED 批次已召回）及未核销子项标识列表。
 */
public record ConditionBlockedResponse(
        String code,
        String reason,
        String message,
        List<String> openItems
) {
}
