package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 发布快照正文：发布时刻固化的编组、类别、区段限制版本与逐项校验结论。
 * 作为 rail_publish_snapshot.snapshot_json 的序列化结构，字段与排序保持稳定。
 *
 * @param cars                编组快照，按车厢位置升序
 * @param categories          编组内 distinct 类别，按字典序
 * @param restrictionVersions 计划路径上限制区段的配置版本，按 sectionId 字典序
 * @param checks              逐项校验结论，固定顺序
 */
public record SnapshotBody(List<CarView> cars,
                           List<String> categories,
                           List<RestrictionVersionView> restrictionVersions,
                           List<HazardCheckView> checks) {
}
