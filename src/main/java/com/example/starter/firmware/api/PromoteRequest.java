package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 金丝雀推进请求。promoteKey 全局唯一，同键同参重放首次结果、异参409、失败不占键。
 * targetLevel 缺省时推进到紧邻下一级别；当前为最高级别时推进进入 COMPLETED 终态。
 */
public record PromoteRequest(
        @NotBlank @Size(max = 64) String promoteKey,
        Integer targetLevel) {
}
