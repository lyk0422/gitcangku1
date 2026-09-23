package com.example.starter.restitution.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记主张请求：案内唯一 claimKey、申请人、非空说明、非空藏品子集。
 */
public record RegisterClaimRequest(
        @NotBlank(message = "claimKey 不能为空")
        @Size(max = 64)
        String claimKey,
        @NotBlank(message = "申请人不能为空")
        @Size(max = 128)
        String applicant,
        @NotBlank(message = "主张说明不能为空")
        @Size(max = 4000)
        String statement,
        @NotEmpty(message = "主张藏品子集不能为空")
        @Size(max = 10, message = "主张藏品至多10个")
        List<String> artifactNos
) {
}
