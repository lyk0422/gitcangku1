package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.Purpose;

/**
 * 子范围清单响应：按主体、用途、代次列出全部子范围（含默认子范围）及各自状态。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      授权代次
 * @param scopes     子范围清单，含默认子范围（scopeKey 为 default）
 */
public record ScopeListResponse(String subjectKey, Purpose purpose, int epoch, List<ScopeEntry> scopes) {

    /**
     * 子范围清单条目。
     *
     * @param scopeKey  子范围标识，default 表示默认子范围
     * @param label     子范围标签；默认子范围无标签，为 null
     * @param status    有效状态：整体代次已撤回时所有子范围均为 REVOKED
     * @param available 当前是否可用：整体代次有效且子范围本身有效时为 true
     */
    public record ScopeEntry(String scopeKey, String label, GrantStatus status, boolean available) {
    }
}
