package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多父合批请求：将 2～5 个当前 RELEASED 父批一次性合成为一个全新子批。
 *
 * <p>parents 为规范化对象集合：服务端按 batchKey 排序后计算权重与指纹，
 * 集合经 JSON 序列化参与指纹，不使用未转义分隔符拼接。
 * 每个父批投入数量 quantity 为正且不得超过该父批登记数量（父批未登记数量时跳过超量校验）。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "childBatchKey 不能为空") String childBatchKey,
        @NotBlank(message = "childBatchNo 不能为空") String childBatchNo,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 5, message = "parents 必须包含 2～5 个父批")
        List<@Valid ParentInput> parents
) {

    /**
     * 合批父投入：父批业务键与投入数量（无量纲业务单位）。
     */
    public record ParentInput(
            @NotBlank(message = "父批 batchKey 不能为空") String batchKey,
            @NotNull(message = "父批投入 quantity 不能为空") BigDecimal quantity
    ) {
    }
}
