package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建缺额轮转选择池请求。成员固定 2～5 个公告，权重为正整数 1～10，创建后不可变。
 *
 * @param requestId 写操作全局唯一幂等键
 * @param operator  操作者，非空
 * @param poolId    选择池编号，全局唯一
 * @param members   池成员列表，公告编号不得重复
 */
public record CreatePoolRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String poolId,
        @NotNull @Size(min = 2, max = 5) List<@Valid Member> members
) {
    /**
     * 池成员参数。
     *
     * @param campaignId 成员公告编号，须已存在
     * @param weight     轮转权重，正整数 1～10
     */
    public record Member(
            @NotBlank @Size(max = 64) String campaignId,
            @NotNull @Min(1) @Max(10) Integer weight
    ) {
    }
}
