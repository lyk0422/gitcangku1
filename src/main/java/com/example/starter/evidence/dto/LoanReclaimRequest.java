package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 逾期追缴请求。仅借出时的保管人可提交；借出记录须已逾期（当前 UTC 时刻不早于应还时刻）。
 * 同一借出记录只能被追缴一次，重复提交按 reclaimKey 幂等返回首次结果。
 *
 * @param commandKey 幂等命令键（requestId）：同键同参重放首次结果，异参 409，失败不占键
 * @param reclaimKey 追缴业务键，全局唯一；同一借出重复追缴按该键幂等返回首次结果
 * @param loanKey    被追缴的借出业务键
 * @param note       追缴说明，非空
 */
public record LoanReclaimRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String reclaimKey,
        @NotBlank @Size(max = 64) String loanKey,
        @NotBlank @Size(max = 512) String note) {
}
