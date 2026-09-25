package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 金丝雀推进请求。promoteKey 全局唯一，同键同参重放首次结果、异参409、失败不占键。
 * targetLevel 必须等于当前已解锁最高级别+1；等于级别总数+1 表示完成发布单（进入 COMPLETED）。
 */
public record PromoteRequest(
        @NotBlank @Size(max = 64) String promoteKey,
        @Min(2) int targetLevel) {
}
