package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 写入时提交的委托边版本引用：处理方按链路顺序提交每条边的 delegationKey 与版本。
 *
 * @param delegationKey 委托边全局唯一键
 * @param version       调用方持有的边版本；与库内当前版本不符时返回 409
 */
public record EdgeVersionInput(
        @NotBlank @Size(max = 128) String delegationKey,
        @NotNull @Min(1) Integer version) {
}
