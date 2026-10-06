package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 归还确认请求。仅借出时的当前保管人可确认，借用人不能代为确认；
 * 必须指定本次借出的 loanKey、封条是否完好及非空说明。
 * 证物已登记剂量上限时，必须携带 1 至 20 段光照区间（完整覆盖实际借出至归还）；
 * 未登记上限的证物不接受剂量区间。
 *
 * @param commandKey 幂等命令键
 * @param loanKey    本次借出的业务键
 * @param sealIntact 封条是否完好：true 完好回到 SEALED / false 异常进入 SEAL_BROKEN
 * @param note       归还说明，非空
 * @param segments   光照区间（UTC，左闭右开、首尾相接、互不重叠）；仅登记剂量上限的证物需要
 */
public record LoanReturnRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String loanKey,
        @NotNull Boolean sealIntact,
        @NotBlank @Size(max = 512) String note,
        @Valid List<DoseSegmentRequest> segments) {

    /**
     * 兼容无剂量区间的归还请求（未登记剂量上限的证物）。
     */
    public LoanReturnRequest(String commandKey, String loanKey, Boolean sealIntact, String note) {
        this(commandKey, loanKey, sealIntact, note, null);
    }
}
