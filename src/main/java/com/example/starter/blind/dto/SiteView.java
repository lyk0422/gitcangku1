package com.example.starter.blind.dto;

/**
 * 研究中心视图；不含区组处理映射，任何角色均不可见盲底。
 *
 * @param siteId        中心编号
 * @param leadActorId   中心负责人操作者编号
 * @param status        INACTIVE / ACTIVE
 * @param activeVersion 当前已启用配置版本号；null 表示从未启用
 * @param createdAt     创建时间，Unix 毫秒，UTC
 * @param activatedAt   最近一次启用时间，Unix 毫秒，UTC；null 表示未启用
 */
public record SiteView(
        String siteId,
        String leadActorId,
        String status,
        Integer activeVersion,
        long createdAt,
        Long activatedAt
) {
}
