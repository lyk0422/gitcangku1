package com.example.starter.blind.dto;

import java.util.List;

/**
 * 中心视图：中心状态与当前启用版本的区组名额；仅负责人可查。
 *
 * @param experimentId  实验编号
 * @param siteId        中心编号
 * @param status        INACTIVE / ACTIVE
 * @param activeVersion 已启用配置版本号；未启用为 null
 * @param blocks        启用版本的区组名额；未启用时为空列表
 * @param createdAt     中心创建时间，Unix 毫秒，UTC
 * @param activatedAt   启用时间，Unix 毫秒，UTC；未启用为 null
 */
public record SiteView(
        String experimentId,
        String siteId,
        String status,
        Integer activeVersion,
        List<BlockQuotaView> blocks,
        long createdAt,
        Long activatedAt
) {
}
