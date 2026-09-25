package com.example.starter.domain;

/**
 * 来源策略对单个制品坐标的要求。
 *
 * @param name             制品名称（坐标）
 * @param requiredLevel    最低证明等级（含），证明等级不足视为不合规
 * @param requiredDigest   要求的构建摘要；为空表示该坐标不校验构建摘要
 */
public record PolicyCoordinate(
        String name,
        int requiredLevel,
        String requiredDigest) {
}
