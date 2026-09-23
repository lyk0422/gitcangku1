package com.example.starter.blind.dto;

/**
 * 代次令牌签发结果。
 *
 * @param tokenId      令牌随机编号，使用时通过 X-Access-Token 头回传
 * @param experimentId 实验编号
 * @param generationId 签发时所属代次主键
 * @param generationNo 签发时代次序号
 * @param actorId      令牌持有人
 * @param role         令牌对应职责角色
 * @param issuedAt     签发时间，Unix 毫秒 UTC
 */
public record AccessTokenView(
        String tokenId,
        String experimentId,
        long generationId,
        int generationNo,
        String actorId,
        String role,
        long issuedAt
) {
}
