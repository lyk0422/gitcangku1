package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 站台能力登记/变更请求（站台管理方）。requestKey 为幂等键；
 * 长度单位米，powerSystems 为站台兼容供电制式集合，accessible 表示是否具备无障碍能力。
 * 任一能力属性相对当前版本变化都会产生新版本并留存历史；完全相同则为幂等无变更。
 */
public record PlatformRegistrationRequest(
        @NotBlank String requestKey,
        @NotBlank String platformName,
        @NotNull @Positive Integer maxLengthMeters,
        @NotEmpty List<@NotBlank String> powerSystems,
        @NotNull Boolean accessible) {
}
