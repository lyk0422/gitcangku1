package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 逾期追缴请求。仅借出时的保管人可提交；reclaimKey 全局唯一兼作幂等键，
 * 重复提交按 reclaimKey 返回首次结果，同键改参返回 409。
 *
 * @param reclaimKey 追缴业务键，全局唯一，兼作幂等键
 * @param loanKey    被追缴的借出业务键
 * @param note       追缴说明，非空
 */
public record LoanReclaimRequest(
        @NotBlank @Size(max = 64) String reclaimKey,
        @NotBlank @Size(max = 64) String loanKey,
        @NotBlank @Size(max = 512) String note) {
}
