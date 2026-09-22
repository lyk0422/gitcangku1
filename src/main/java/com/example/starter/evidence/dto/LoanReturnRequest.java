package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 归还确认请求。仅借出时的当前保管人可确认，借用人不能代为确认；
 * 必须指定本次借出的 loanKey、封条是否完好及非空说明。
 *
 * @param commandKey 幂等命令键
 * @param loanKey    本次借出的业务键
 * @param sealIntact 封条是否完好：true 完好回到 SEALED / false 异常进入 SEAL_BROKEN
 * @param note       归还说明，非空
 */
public record LoanReturnRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String loanKey,
        @NotNull Boolean sealIntact,
        @NotBlank @Size(max = 512) String note) {
}
