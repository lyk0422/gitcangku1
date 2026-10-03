package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 创建乘务组请求。requestKey 为幂等键；成员名册 1～20 人，组建后不可变。
 */
public record CrewGroupRequest(
        @NotBlank String requestKey,
        @NotBlank String groupCode,
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> memberCodes) {
}