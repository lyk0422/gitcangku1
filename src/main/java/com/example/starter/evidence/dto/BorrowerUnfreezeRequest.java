package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 借出人解冻请求。须由另一名保管人（不同于触发冻结的追缴保管人且非借出人本人）提交；
 * 解冻后追缴计数从零重新累计，历史追缴记录保留。
 *
 * @param commandKey 幂等命令键（requestId）：同键同参重放首次结果，异参 409，失败不占键
 * @param note       解冻说明，非空
 */
public record BorrowerUnfreezeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String note) {
}
