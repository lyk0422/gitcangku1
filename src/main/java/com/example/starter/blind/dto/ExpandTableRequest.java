package com.example.starter.blind.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 扩容请求体：在显式引用的已封存版本之后新建后继随机表版本。
 *
 * @param predecessorSealId 显式引用的前一已封存版本编号，必须为该区组最新版本
 * @param capacity          新版本容量（新增序号数），必须为正数且为 4 的整数倍，单位：个序号
 * @param expectedDigest    引用版本的预期摘要；提供时必须一致，否则 422
 */
public record ExpandTableRequest(
        @NotBlank(message = "predecessorSealId 不能为空")
        String predecessorSealId,

        @NotNull(message = "capacity 不能为空")
        @Min(value = 1, message = "capacity 必须为正数")
        Integer capacity,

        String expectedDigest
) {
}
