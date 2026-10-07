package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建缺额轮转池请求。成员 2～5 个、公告号互不重复、权重 1～10，创建后固定。
 *
 * @param requestId 写操作全局唯一幂等键
 * @param operator  操作者标识，非空
 * @param poolId    轮转池编号，全局唯一
 * @param members   池成员列表（公告须已存在，沿用其两级 UTC 日额度）
 */
public record CreatePoolRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String poolId,
        @NotNull @Size(min = 2, max = 5) List<@Valid PoolMemberRequest> members
) {

    /**
     * 池成员。
     *
     * @param campaignId 已存在的公告编号
     * @param weight     正整数权重，取值 1～10
     */
    public record PoolMemberRequest(
            @NotBlank @Size(max = 64) String campaignId,
            @NotNull @Min(1) @Max(10) Integer weight
    ) {
    }
}
