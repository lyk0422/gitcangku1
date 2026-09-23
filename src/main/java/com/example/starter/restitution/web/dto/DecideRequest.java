package com.example.starter.restitution.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 裁决提交请求：expectedVersion 为提交方依据的案件版本，claimKeys 为1至10个不同主张编号。
 */
public record DecideRequest(
        @NotNull(message = "expectedVersion 不能为空")
        Long expectedVersion,
        @NotEmpty(message = "至少选定一个主张")
        @Size(max = 10, message = "至多选定10个主张")
        List<String> claimKeys
) {
}
