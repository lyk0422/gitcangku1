package com.example.starter.consent.catalog.dto;

/**
 * 预览中的已撤回授权项：迁移不得改写历史撤回链，仅作告知，不参与激活提交。
 *
 * @param subjectKey 主体标识
 * @param epoch      已撤回的授权代次
 */
public record PreviewRevokedGrant(String subjectKey, int epoch) {
}
