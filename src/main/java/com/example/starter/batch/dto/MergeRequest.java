package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求体：2～5 个全部当前可用的 RELEASED 父批合并为一个全新产物批次。
 * 父批合批后置为 MERGED；产物初始 QUARANTINED，必做检验项取各父批必做项的并集，
 * 且必须按「所有父物料」分别重新绑定合格证后才能放行。操作者通过 X-Actor-Id 请求头提供。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "childBatchKey 不能为空") String childBatchKey,
        @NotBlank(message = "childBatchNo 不能为空") String childBatchNo,
        @NotBlank(message = "childProductCode 不能为空") String childProductCode,
        @NotEmpty(message = "parents 不能为空")
        @Size(min = 2, max = 5, message = "parents 必须包含 2～5 个父批")
        List<@Valid ParentRef> parents
) {

    /**
     * 父批引用：仅需批次业务键；批号、物料等以库中父批为准。
     */
    public record ParentRef(
            @NotBlank(message = "父批 batchKey 不能为空") String batchKey
    ) {
    }
}
