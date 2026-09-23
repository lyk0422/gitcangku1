package com.example.starter.aliquot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 联合取样申请请求。当前保管人申请从 2～20 件不同母样各取正整数数量；
 * requestId 为幂等命令键，aliquotKey 为取样单唯一业务键。
 *
 * @param commandKey 幂等命令键（requestId）：同参集合换序重放返回首次结果，异参 409，失败不占键
 * @param aliquotKey 联合取样单业务键，全局唯一，复用 409
 * @param items      母样取用量列表，须 2～20 件、键互不相同、数量均为正整数
 */
public record AliquotApplyRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 128) String aliquotKey,
        @Valid @Size(min = 2, max = 20) List<AliquotItemInput> items) {
}
