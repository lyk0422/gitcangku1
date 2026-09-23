package com.example.starter.restitution.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建案件请求：1至10个案内唯一、非空藏品编号。
 */
public record CreateCaseRequest(
        @NotEmpty(message = "藏品编号列表不能为空")
        @Size(max = 10, message = "藏品编号至多10个")
        List<String> artifactNos
) {
}
