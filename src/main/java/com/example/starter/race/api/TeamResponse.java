package com.example.starter.race.api;

import java.util.List;

/**
 * 创建团队成功响应。
 *
 * @param raceId    赛事ID
 * @param teamCode  团队代码
 * @param version   创建成功后的赛事版本号
 * @param members   成员参赛号列表，按参赛号字典序排列
 * @param createdAt 创建时间，Unix毫秒时间戳
 */
public record TeamResponse(
        String raceId,
        String teamCode,
        int version,
        List<String> members,
        long createdAt
) {
}
