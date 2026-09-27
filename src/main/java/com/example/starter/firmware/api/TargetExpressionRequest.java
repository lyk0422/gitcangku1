package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 目标标签表达式请求。三组条件语义：
 * include 设备标签必须全部包含；exclude 设备标签一个都不得包含；
 * anyOf 非空时设备标签需至少命中一项。允许传入重复标签，服务端规范化去重后再校验语义。
 *
 * @param include 必须包含标签（可空或省略，表示不限制）
 * @param exclude 不得包含标签（可空或省略，表示不限制）
 * @param anyOf   至少包含一项标签（可空或省略，表示不限制）
 */
public record TargetExpressionRequest(
        List<@NotBlank @Size(max = 64) String> include,
        List<@NotBlank @Size(max = 64) String> exclude,
        List<@NotBlank @Size(max = 64) String> anyOf) {
}
