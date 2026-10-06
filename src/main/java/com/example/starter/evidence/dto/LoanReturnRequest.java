package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 归还确认请求。仅借出时的当前保管人可确认，借用人不能代为确认；
 * 必须指定本次借出的 loanKey、封条是否完好及非空说明。
 * 已登记剂量上限的证物封条完好归还时，必须携带1至20段剂量段；
 * 未登记上限的借出不受剂量规则影响，segments 忽略。
 *
 * @param commandKey 幂等命令键
 * @param loanKey    本次借出的业务键
 * @param sealIntact 封条是否完好：true 完好回到 SEALED / false 异常进入 SEAL_BROKEN
 * @param note       归还说明，非空
 * @param segments   剂量段（UTC区间+非负照度），1至20段；null 表示未携带
 */
public record LoanReturnRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String loanKey,
        @NotNull Boolean sealIntact,
        @NotBlank @Size(max = 512) String note,
        @Size(min = 1, max = 20) List<@Valid DoseSegmentRequest> segments) {

    /**
     * 不携带剂量段的归还请求（未登记剂量上限的借出使用）。
     */
    public LoanReturnRequest(String commandKey, String loanKey, Boolean sealIntact, String note) {
        this(commandKey, loanKey, sealIntact, note, null);
    }
}
