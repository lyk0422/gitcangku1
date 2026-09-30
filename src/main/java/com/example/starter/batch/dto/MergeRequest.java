package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求体：两个当前可用的 RELEASED 父批合并为一个新批次；
 * 任一父批存在未处置仓储温度偏差时整单失败（422），不创建新批、不改写父批。
 * 子批继承产品编码（两父批须一致）、生产 UTC 时间与必做检验项（取并集），初始 QUARANTINED。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "mergeKey 不能为空") String mergeKey,
        @NotBlank(message = "batchNo 不能为空") String batchNo,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 2, message = "parents 必须且只能包含 2 个父批")
        List<@Valid ParentRef> parents
) {

    /**
     * 父批引用：仅提供父批业务键；产品编码等以库中当前数据为准。
     */
    public record ParentRef(
            @NotBlank(message = "父批 batchKey 不能为空") String batchKey
    ) {
    }
}
