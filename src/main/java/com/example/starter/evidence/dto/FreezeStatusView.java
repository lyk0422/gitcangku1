package com.example.starter.evidence.dto;

/**
 * 证物冻结状态视图。
 *
 * @param evidenceKey    证物业务键
 * @param frozen        是否被未终结（PENDING/APPROVED）销毁令冻结
 * @param destructionKey 冻结它的销毁令业务键；未冻结时为 null
 */
public record FreezeStatusView(
        String evidenceKey,
        boolean frozen,
        String destructionKey) {
}
