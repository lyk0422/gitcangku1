package com.example.starter.restitution.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建案件请求：1～10 个案内唯一藏品编号。
 *
 * @param items 藏品编号列表，顺序保留，重复或为空由业务校验拒绝（400）
 */
public record CreateCaseRequest(
        @NotEmpty(message = "items 不能为空")
        @Size(min = 1, max = 10, message = "藏品数量必须在 1 至 10 之间")
        List<@NotBlank(message = "藏品编号不能为空") String> items) {
}
