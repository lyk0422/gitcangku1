package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 电芯入批请求。
 *
 * @param requestId 幂等请求键
 * @param cellCode 电芯编号，合成编号
 * @param initialVoltage 入批初始电压，单位伏特(V)
 */
public record EnrollCellRequest(
        @NotBlank(message = "requestId 不能为空") @Size(max = 64) String requestId,
        @NotBlank(message = "cellCode 不能为空") @Size(max = 64) String cellCode,
        @NotNull(message = "initialVoltage 不能为空") @DecimalMin(value = "0", message = "initialVoltage 不能为负")
        BigDecimal initialVoltage) {
}