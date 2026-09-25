package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 接收方证明提交（续签）请求：每个接收方对每个“用途＋授权代次”仅允许一条生效证明。
 *
 * <p>证明作用域精确到用途与代次：用途拆分或迁移后，旧用途/旧代次证明不得为新代次复用。
 * 再次提交即续签，生成新版本（版本号从 1 递增），旧版本置为 SUPERSEDED 而非覆盖删除。
 *
 * @param requestId   幂等请求标识；attestKey 指纹含接收方、用途代次、到期与声明摘要
 * @param recipientId 接收方标识
 * @param purpose     证明所对应的授权用途
 * @param epoch       证明所对应的授权代次，从 1 开始
 * @param expiresAt   证明到期时刻（UTC），必须晚于提交时刻
 * @param claimDigest 声明摘要（合成字符串）
 */
public record AttestSubmitRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientId,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @NotNull Instant expiresAt,
        @NotBlank @Size(max = 512) String claimDigest) {
}
