package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 不可变归还快照实体，对应 package_snapshot 表。
 * RETURN：每批归还写入一条本批快照；CLOSE：最后一件归还时写入含全部借出明细与全部归还批次的关闭快照。
 *
 * @param id           主键
 * @param packageId    所属组合包主键
 * @param snapshotType 快照类型：RETURN 批次归还 / CLOSE 全部归还关闭
 * @param batchId      RETURN 快照对应批次主键；CLOSE 快照为 null
 * @param snapshotJson 不可变快照内容 JSON
 * @param createdAt    快照生成时间（Asia/Shanghai）
 */
public record PackageSnapshot(
        Long id,
        Long packageId,
        String snapshotType,
        Long batchId,
        String snapshotJson,
        LocalDateTime createdAt) {

    public static final String TYPE_RETURN = "RETURN";
    public static final String TYPE_CLOSE = "CLOSE";
}
