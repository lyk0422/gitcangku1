package com.example.starter.api.dto;

import java.util.List;

/**
 * 单张锁定图的许可证命中与告知校验结果（查询与发布失败均使用此结构）。
 *
 * @param lockFileId 锁定图 ID
 * @param rootName   根制品名称
 * @param hits       命中策略的制品路径（直接或传递）
 * @param missing    缺失或不合规告知明细；为空表示该图校验通过
 */
public record LicenseCheckView(
        long lockFileId,
        String rootName,
        List<Hit> hits,
        List<MissingNotice> missing) {

    /**
     * 一条策略命中路径。
     *
     * @param name          命中制品名称
     * @param version       命中制品精确版本
     * @param direct        是否根制品直接依赖（含根本身）
     * @param path          从根到该制品的依赖路径（名称:版本 有序列表）
     * @param scopeType     命中策略作用域：LOCK / COORDINATE
     * @param licenseId     命中策略许可证标识
     * @param action        命中策略动作：NOTICE_REQUIRED / ALLOWED
     * @param noticeKey     已绑定告知文本标识；未绑定为空
     * @param noticeVersion 已绑定告知文本版本；未绑定为空
     * @param noticeRegions 已绑定文本的地区集合；未绑定为空
     */
    public record Hit(
            String name,
            int version,
            boolean direct,
            List<String> path,
            String scopeType,
            String licenseId,
            String action,
            String noticeKey,
            Integer noticeVersion,
            List<String> noticeRegions) {
    }

    /**
     * 一条告知缺失/不合规明细，422 时稳定返回。
     *
     * @param reason   原因码：NOTICE_MISSING / TEXT_NOT_APPROVED / REGION_NOT_COVERED / TEXT_WITHDRAWN
     * @param name     制品名称
     * @param version  制品精确版本
     * @param direct   是否直接依赖（含根本身）
     * @param path     从根到该制品的依赖路径
     * @param licenseId 许可证标识
     * @param detail   可读补充说明
     */
    public record MissingNotice(
            String reason,
            String name,
            int version,
            boolean direct,
            List<String> path,
            String licenseId,
            String detail) {
    }
}
