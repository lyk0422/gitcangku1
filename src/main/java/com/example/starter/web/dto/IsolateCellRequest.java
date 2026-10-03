package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 异常电芯隔离请求。
 *
 * @param cellCode 电芯编号
 * @param reason 隔离原因
 */
public record IsolateCellRequest(
        @NotBlank(message = "cellCode 不能为空") @Size(max = 64) String cellCode,
        @NotBlank(message = "reason 不能为空") @Size(max = 255) String reason) {
}