package com.example.starter.consent.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量续签项：按旧委托版本续签一份委托的有效期。
 *
 * @param delegateKey     委托键
 * @param expectedVersion 期望的旧委托版本，与当前版本不一致则整批不生效
 * @param validFrom       新有效期起（UTC，左闭）
 * @param validTo         新有效期止（UTC，右开），必须晚于 validFrom
 */
public record DelegateRenewItem(
        @NotBlank @Size(max = 128) String delegateKey,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull Instant validFrom,
        @NotNull Instant validTo) {
}
