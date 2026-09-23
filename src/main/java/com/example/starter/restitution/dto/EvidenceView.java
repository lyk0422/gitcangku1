package com.example.starter.restitution.dto;

/**
 * 证据视图：含撤销状态，历史保留可见。
 *
 * @param evidenceKey 案内唯一证据键
 * @param summary     证据摘要
 * @param version     该证据创建时主张所处的证据版本
 * @param active      是否有效（false 表示已撤销）
 */
public record EvidenceView(String evidenceKey, String summary, long version, boolean active) {
}
