package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 联合锁定中的一个精确根版本：名称 + 正整数版本号，锁定时固定不可回退。
 */
public record MultiLockRoot(
        @NotBlank String name,
        @Positive int version) {
}
