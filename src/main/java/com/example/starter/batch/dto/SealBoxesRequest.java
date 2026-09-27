package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量封箱请求：一次提交一个完整封箱集合，服务端先校验完整最终集合，任一标签失败整次回滚。
 *
 * @param boxes 封箱条目，1～200 条；同一请求内 sealKey、labelNo 均不可重复
 */
public record SealBoxesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "boxes 不能为 null")
        @Size(min = 1, max = 200, message = "一次封箱必须包含 1～200 个条目")
        List<@Valid Box> boxes
) {

    /**
     * 单个封箱条目。
     *
     * @param sealKey  封箱业务键，批次内全局唯一（作废后也不可复用），用于作废与版本追溯
     * @param labelNo  标签号，必须落在批次登记号段 [labelRangeStart, labelRangeEnd] 内
     * @param quantity 本箱数量，正整数；同批次全部活跃封箱数量之和不得超过计划数量
     */
    public record Box(
            @NotBlank(message = "sealKey 不能为空") String sealKey,
            @Positive(message = "labelNo 必须为正整数") long labelNo,
            @Positive(message = "quantity 必须为正整数") long quantity
    ) {
    }
}
