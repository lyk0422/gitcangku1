package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 召回处置提交请求体（一审，质量负责人，通过 X-Actor-Id 传入）。
 * ancestorKey 必须为 RECALLED 批次；destroy/rework/hold 三个集合互斥，
 * 并集必须恰好等于“祖先自身 + 当前全部后代”的闭包，否则 422。
 * hold 非空时 holdReason 必填。集合内部顺序不参与幂等指纹（换序同参）。
 */
public record DispositionSubmitRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "dispositionKey 不能为空") String dispositionKey,
        @NotBlank(message = "ancestorKey 不能为空") String ancestorKey,
        @NotNull(message = "destroy 集合不能为 null") List<String> destroy,
        @NotNull(message = "rework 集合不能为 null") List<String> rework,
        @NotNull(message = "hold 集合不能为 null") List<String> hold,
        String holdReason
) {
}
