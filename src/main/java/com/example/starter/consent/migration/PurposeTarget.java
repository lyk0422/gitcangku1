package com.example.starter.consent.migration;

/**
 * 拆分迁移中的新用途规格。
 *
 * @param purpose     新用途代码，同一次迁移内唯一
 * @param range       左闭右开处理范围，必须为旧用途范围的子集
 * @param supersedes  替代的用途代码；为 {@code null} 表示无替代关系
 */
public record PurposeTarget(String purpose, LongRange range, String supersedes) {

    public long rangeStart() {
        return range.start();
    }

    public long rangeEnd() {
        return range.end();
    }
}
