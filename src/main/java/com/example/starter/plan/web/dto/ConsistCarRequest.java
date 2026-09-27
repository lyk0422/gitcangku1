package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 危险品编组车厢登记项。
 *
 * @param position     车厢编组位置（车厢号，从 1 开始），同一计划内不可重复
 * @param hazardClass  危险品类别代码；为 {@code null} 表示普通车厢；非空时为 1～32 位大写字母/数字
 */
public record ConsistCarRequest(
        @NotNull @Positive Integer position,
        @Pattern(regexp = "[A-Z0-9]{1,32}", message = "危险品类别必须为 1~32 位大写字母或数字")
        @Size(max = 32) String hazardClass) {
}
