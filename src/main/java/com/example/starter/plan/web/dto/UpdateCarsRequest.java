package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换草稿计划危险品编组请求。requestKey 为幂等键；operator 为操作者标识，
 * 参与幂等指纹；expectedVersion 必须与当前版本一致，成功版本加一。
 * cars 为空表示清空编组（该计划不承运危险品）。
 */
public record UpdateCarsRequest(
        @NotBlank String requestKey,
        @NotBlank @Size(max = 128) String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Size(max = 200) List<@Valid CarRequest> cars) {
}
