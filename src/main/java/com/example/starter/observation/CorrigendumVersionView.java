package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 附录版本链中的单个版本视图：版本 0 为只读原始快照，其后每个版本对应一份更正附录。
 *
 * @param corrigendumVersion 附录版本号；0 表示原始快照
 * @param snapshotVersion    该版本引用的观测快照版本号（原始快照即自身版本，附录版本为附录记录的引用）
 * @param operator           操作者；原始快照版本为 null
 * @param submittedAtUtc     版本生成时刻（UTC，ISO-8601）；原始快照为快照落库时刻，附录为提交时刻
 * @param changes            本版本相对前一版本的字段差异；原始快照版本为空列表
 * @param location           截至该版本地点的有效值
 * @param reading            截至该版本读数的有效值（十进制原文）
 * @param note               截至该版本备注的有效值
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CorrigendumVersionView(
        int corrigendumVersion,
        int snapshotVersion,
        String operator,
        Instant submittedAtUtc,
        List<FieldChange> changes,
        String location,
        String reading,
        String note) {
}
