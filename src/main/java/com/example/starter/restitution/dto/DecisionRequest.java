package com.example.starter.restitution.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 裁决提交请求。
 *
 * @param expectedVersion 调用方预期的案件版本，与库内不一致返回 409
 * @param claimKeys       选定的 1～10 个不同 claimKey；须恰好覆盖全案藏品
 */
public record DecisionRequest(
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion,
        @NotEmpty(message = "claimKeys 不能为空")
        @Size(min = 1, max = 10, message = "选定主张数量必须在 1 至 10 之间")
        List<@jakarta.validation.constraints.NotBlank(message = "claimKey 不能为空") String> claimKeys) {
}
