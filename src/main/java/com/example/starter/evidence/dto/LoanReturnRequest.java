package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 归还确认请求。仅借出时的当前保管人可确认，借用人不能代为确认；
 * 必须指定本次借出的 loanKey、封条是否完好及非空说明。
 * 证物已登记光照上限且封条完好时，必须携带 1 至 20 段互不重叠、
 * 完整覆盖实际借出至归还的 UTC 区间与非负照度，以及与账本当前版本一致的期望版本；
 * 未登记上限的借出不受新规则影响（segments/expectedVersion 须为空）。
 *
 * @param commandKey      幂等命令键
 * @param loanKey         本次借出的业务键
 * @param sealIntact      封条是否完好：true 完好回到 SEALED / false 异常进入 SEAL_BROKEN
 * @param note            归还说明，非空
 * @param expectedVersion 期望账本版本，须等于账本当前版本；未登记上限时为 null
 * @param segments        光照剂量段（1-20 段）；未登记上限或封条异常时为 null
 */
public record LoanReturnRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String loanKey,
        @NotNull Boolean sealIntact,
        @NotBlank @Size(max = 512) String note,
        Long expectedVersion,
        @Size(max = 20) List<@Valid DoseSegmentRequest> segments) {

    /**
     * 兼容无剂量信息的归还请求（未登记上限的证物）。
     */
    public LoanReturnRequest(String commandKey, String loanKey, Boolean sealIntact, String note) {
        this(commandKey, loanKey, sealIntact, note, null, null);
    }
}
