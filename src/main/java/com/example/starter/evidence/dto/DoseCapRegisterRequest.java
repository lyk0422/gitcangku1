package com.example.starter.evidence.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 剂量上限首次登记请求。仅当前保管人可为 SEALED 证物登记，登记后不可变。
 * 幂等身份包含操作、操作者、资源及全部参数和期望版本。
 *
 * @param requestId      幂等请求键，全局唯一
 * @param capLuxMinutes  不可逆光照上限（勒克斯分钟），正值最多3位小数
 * @param expectedVersion 期望账本版本：首次登记必须为0（账本尚不存在）
 */
public record DoseCapRegisterRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 3)
        BigDecimal capLuxMinutes,
        @NotNull @Min(0) Integer expectedVersion) {
}
