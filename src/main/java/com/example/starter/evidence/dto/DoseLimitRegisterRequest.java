package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 剂量上限登记请求。仅当前保管人可为 SEALED 证物首次登记，登记后不可变。
 * 幂等身份由操作、操作者、证物与全部参数（含期望版本）结构化指纹构成。
 *
 * @param requestId       幂等请求键，全局唯一；同键同参重放返回首次结果，同键改参返回 409
 * @param limitLuxMinutes 剂量上限，单位勒克斯分钟，正值最多3位小数
 * @param expectedVersion 期望账本版本：可选；登记前账本不存在（版本 0），提供时必须为 0，否则 409
 */
public record DoseLimitRegisterRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Positive @Digits(integer = 15, fraction = 3) BigDecimal limitLuxMinutes,
        Long expectedVersion) {
}
