package com.example.starter.blind.dto;

import java.util.List;

/**
 * 中心配置版本视图；版本号与其区组名额内容。
 *
 * @param experimentId 实验编号
 * @param siteId       中心编号
 * @param version      配置版本号，从 1 开始
 * @param blocks       该版本固定的区组名额，顺序按区组编号排序
 * @param createdAt    版本提交时间，Unix 毫秒，UTC
 */
public record SiteConfigView(
        String experimentId,
        String siteId,
        int version,
        List<BlockQuotaView> blocks,
        long createdAt
) {
}
