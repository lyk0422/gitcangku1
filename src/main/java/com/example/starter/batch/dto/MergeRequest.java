package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求体。2～5 个不同且当前可用的 RELEASED 父批，产品编码及必做检验项集合必须相同；
 * 父批集合顺序不影响幂等同参判定。新批 batchKey 全局唯一，初始 QUARANTINED，
 * 生产 UTC 时间取父批最晚值，不继承检验与批准；父批全部置为 MERGED 并退出可用集合。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "新批 batchKey 不能为空") String batchKey,
        @NotBlank(message = "新批 batchNo 不能为空") String batchNo,
        @NotNull(message = "parentBatchKeys 不能为空")
        @Size(min = 2, max = 5, message = "parentBatchKeys 必须包含 2～5 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parentBatchKeys
) {
}
