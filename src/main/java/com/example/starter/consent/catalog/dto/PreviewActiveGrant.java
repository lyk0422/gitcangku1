package com.example.starter.consent.catalog.dto;

import java.util.List;

/**
 * 预览中的有效授权项：激活时必须原样回传其 expectedVersion；
 * 迁移提交后将按旧用途范围拆分为 splitInto 中每个新用途的授权。
 *
 * @param subjectKey      主体标识
 * @param epoch           当前有效授权代次
 * @param expectedVersion 授权行版本，预览与激活之间被撤回或迁移即变化
 * @param splitInto       将拆分出的新用途代码列表（稳定排序）
 */
public record PreviewActiveGrant(String subjectKey, int epoch, long expectedVersion, List<String> splitInto) {
}
