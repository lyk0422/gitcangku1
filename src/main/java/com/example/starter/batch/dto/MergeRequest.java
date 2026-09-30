package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求体。parents 为 2～10 个父批业务键，按集合处理（去重、按业务键稳定排序后加锁与参与指纹，
 * 因此父批换序视为同参重放）；childBatchKey/childBatchNo/productCode 描述合批产生的全新子批。
 *
 * <p>合批前置条件（任一不满足整单 422，不生成子批、不改变任何父批）：
 * 全部父批均已 RELEASED（未隔离）、规范化过敏原集合完全相同、当前清洁代次为同一有效值。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "childBatchKey 不能为空") String childBatchKey,
        @NotBlank(message = "childBatchNo 不能为空") String childBatchNo,
        @NotBlank(message = "productCode 不能为空") String productCode,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 10, message = "parents 必须包含 2～10 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parents
) {
}
