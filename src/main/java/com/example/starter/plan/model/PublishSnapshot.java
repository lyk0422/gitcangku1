package com.example.starter.plan.model;

import java.util.List;

/**
 * 发布快照：固化发布时刻的配置版本、校验结论、危险品编组与限制区段开放清单。
 * 追加后不可变，取消计划、改签或配置更新均不改写。
 *
 * @param id                 主键
 * @param planId             发布计划 id
 * @param configVersionId    发布时刻生效配置版本 id；无生效配置时为 null
 * @param configVersionNo    配置版本号副本；无生效配置时为 null
 * @param configFingerprint  配置内容指纹副本；无生效配置时为 null
 * @param conclusion         校验结论（PASS）
 * @param operator           发布操作者
 * @param createdAt          快照创建时刻，UTC 毫秒
 * @param cars               固化的危险品编组车厢（按列车、位置升序）
 * @param openSections       固化的限制区段开放清单（按区段、类别升序）
 */
public record PublishSnapshot(long id, long planId, Long configVersionId, Integer configVersionNo,
                              String configFingerprint, String conclusion, String operator,
                              long createdAt, List<SnapshotHazardCar> cars,
                              List<SnapshotOpenSection> openSections) {

    /**
     * 快照中的危险品车厢副本。
     *
     * @param trainNo     列车编号
     * @param position    车厢位置，从 1 开始
     * @param hazardClass 危险品类别
     */
    public record SnapshotHazardCar(String trainNo, int position, String hazardClass) {
    }

    /**
     * 快照中的限制区段开放条目副本。
     *
     * @param sectionId   限制区段 ID
     * @param hazardClass 开放的危险品类别
     */
    public record SnapshotOpenSection(String sectionId, String hazardClass) {
    }
}
