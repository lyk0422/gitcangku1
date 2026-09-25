package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量更新抑制名单请求。先校验完整最终区间集合：任一重叠或起止非法整批 422，
 * 原名单不变；expectedVersion 与当前活动版本不一致返回 409。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param expectedVersion 活动版本（乐观锁），须等于当前版本
 * @param items           本批新增区间，至少一条；与现有生效区间及批内区间均不得重叠
 */
public record BatchUpdateSuppressionListRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Long expectedVersion,
        @NotEmpty @Valid List<Item> items
) {
    /**
     * 批量新增的单条区间。
     *
     * @param visitorId     合成访客编号
     * @param validFromUtc  生效起始时刻（含），epoch 毫秒，UTC
     * @param validUntilUtc 生效结束时刻（不含），epoch 毫秒，UTC；必须大于起始时刻
     */
    public record Item(
            @NotBlank @Size(max = 64) String visitorId,
            @NotNull Long validFromUtc,
            @NotNull Long validUntilUtc
    ) {
    }
}
