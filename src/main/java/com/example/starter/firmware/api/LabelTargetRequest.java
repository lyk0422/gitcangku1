package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单标签目标表达式。三组条件均可选，但整体不能三组全空（空条件 422）；
 * 组内重复标签、同标签同时出现在必须包含与不得包含中均为 422。
 *
 * @param mustInclude 必须包含全部的标签
 * @param mustExclude 不得包含任一的标签
 * @param includeAny  至少包含一项的标签
 */
public record LabelTargetRequest(List<String> mustInclude, List<String> mustExclude, List<String> includeAny) {
}
