package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 召回处置单提交请求体。祖先批次由路径给出（提交时必须为 RECALLED）。
 * 三个互斥集合 destroy/rework/hold 的并集必须恰好等于祖先当前后代闭包（含祖先自身）；
 * 集合内部顺序无关（换序视为同参重放），重复、遗漏、多余或对非召回链批次分类均 422。
 * 提交人通过 X-Actor-Id（QUALITY）提供；requestId 为该提交命令的幂等键。
 */
public record DispositionSubmitRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "dispositionKey 不能为空") String dispositionKey,
        @NotBlank(message = "holdReason 不能为空") String holdReason,
        @NotNull(message = "destroy 集合不能为 null")
        @Size(max = 1000, message = "destroy 集合过大") List<String> destroy,
        @NotNull(message = "rework 集合不能为 null")
        @Size(max = 1000, message = "rework 集合过大") List<String> rework,
        @NotNull(message = "hold 集合不能为 null")
        @Size(max = 1000, message = "hold 集合过大") List<String> hold
) {
}
