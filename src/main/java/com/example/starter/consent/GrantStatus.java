package com.example.starter.consent;

/**
 * 授权代次状态。
 *
 * <ul>
 *   <li>ACTIVE：有效，可写入、可用于固定目录代次的查询；</li>
 *   <li>REVOKED：已撤回，数据物理保留但隔离不可见；</li>
 *   <li>MIGRATED：授权随用途目录迁移被拆分，旧授权不再用于新查询（历史撤回链不改写）。</li>
 * </ul>
 */
public enum GrantStatus {
    ACTIVE,
    REVOKED,
    MIGRATED
}
