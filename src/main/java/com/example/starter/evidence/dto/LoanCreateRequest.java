package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 限时借出请求。仅当前保管人可发起，证物须为 SEALED；借用人必须与保管人不同。
 * dueAt 为 UTC 应还时刻（不带时区偏移，按 UTC 解释），必须晚于服务端当前时刻且不超过 72 小时。
 *
 * @param commandKey 幂等命令键
 * @param loanKey    借出业务键，全局唯一，归还后不可复用
 * @param borrowerId 实际借用人
 * @param purpose    借用用途
 * @param dueAt      UTC 应还时刻
 */
public record LoanCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String loanKey,
        @NotBlank @Size(max = 64) String borrowerId,
        @NotBlank @Size(max = 512) String purpose,
        @NotNull LocalDateTime dueAt) {
}
