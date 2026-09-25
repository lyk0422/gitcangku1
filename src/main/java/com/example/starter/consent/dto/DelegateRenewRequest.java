package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量续签请求：先校验每份旧委托版本，任一冲突整批不生效（409 并逐条列出原因）。
 *
 * @param requestId 幂等请求标识
 * @param items     续签项列表，至少一条
 */
public record DelegateRenewRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty List<@Valid RenewItem> items) {

    /**
     * 单条续签项：按旧委托指纹定位，校验版本后生成版本递增、区间更新的新委托。
     *
     * @param delegateKey     旧委托指纹
     * @param expectedVersion 期望的旧委托当前版本，不一致则整批冲突
     * @param validFrom       新 UTC 有效期起点（含）
     * @param validTo         新 UTC 有效期终点（不含），必须晚于起点
     */
    public record RenewItem(
            @NotBlank @Size(max = 64) String delegateKey,
            @Min(1) int expectedVersion,
            @NotNull Instant validFrom,
            @NotNull Instant validTo) {
    }
}
