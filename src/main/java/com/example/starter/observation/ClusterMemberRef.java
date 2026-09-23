package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 簇归并提交中的成员记录引用：记录键 + 审核人预览时冻结的 generation。
 *
 * @param recordKey  成员观测记录键
 * @param generation 预览冻结的 generation（当时 version）；确认时不匹配返回 409
 */
public record ClusterMemberRef(
        @NotBlank @Size(max = 64) String recordKey,
        @NotNull @Min(1) Integer generation) {
}
