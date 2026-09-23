package com.example.starter.evidence.aliquot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 联合取样申请请求：当前保管人从 2~20 件不同母样各取正整数数量，并指定唯一 aliquotKey。
 *
 * @param commandKey 幂等命令键
 * @param requestId  联合取样申请业务键，全局唯一，同参集合换序重放返回首次结果，异参 409，失败不占键
 * @param aliquotKey 申请指定的唯一子样业务键，换请求复用返回 409
 * @param items      母样取用项，2~20 件，母样键互不相同，每项数量为正整数
 */
public record SamplingApplyRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String aliquotKey,
        @NotEmpty @Valid @Size(min = 2, max = 20) List<SamplingItemInput> items) {
}
