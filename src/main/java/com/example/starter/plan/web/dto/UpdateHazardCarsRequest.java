package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换计划危险品编组请求。requestKey 为幂等键；operator 为操作者标识；
 * expectedVersion 必须与计划当前版本一致；cars 为空表示清空编组。
 */
public record UpdateHazardCarsRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Size(max = 999) List<@Valid HazardCarRequest> cars) {
}
