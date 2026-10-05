package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 光照上限首次登记请求。仅当前保管人可为 SEALED 证物登记，登记后不可变；
 * 幂等键为 requestId，指纹含操作、操作者、资源、全部参数与期望版本。
 *
 * @param requestId       幂等请求键，全局唯一
 * @param limitLuxMinutes 不可逆光照上限，单位勒克斯分钟，正值最多3位小数
 * @param expectedVersion 期望账本版本：首次登记必须为 0（账本尚不存在）
 */
public record DoseLimitRegisterRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull BigDecimal limitLuxMinutes,
        @NotNull Long expectedVersion) {
}
