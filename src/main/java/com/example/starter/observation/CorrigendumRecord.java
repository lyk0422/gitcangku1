package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 不可变更正附录记录：对应 observation_corrigendum 表的一行，成功提交后原子落库，永不修改或删除。
 *
 * <p>版本编号独立于观测快照版本：首份附录 corrigendum_version = 1，previous_version = 0；
 * 之后每份附录的 previous_version 指向前一份附录。原始快照（快照版本链）始终只读，
 * 附录仅表达字段差异，读取时按版本叠加得到当时有效值。
 *
 * @param corrigendumId     全局唯一附录标识
 * @param observationId     观测记录唯一标识
 * @param requestId         生成该附录的请求标识（requestId）
 * @param corrigendumVersion 附录版本号，从 1 开始单调递增
 * @param previousVersion   前一附录版本号；首份附录为 0（表示直接引用原始快照）
 * @param snapshotVersion   引用的原始快照版本号（簇裁决生效后的观测版本）
 * @param changes           字段差异列表（JSON 存储，固定字段顺序 location/reading/note）
 * @param operator          提交附录的观测记录员标识
 * @param submittedAtUtc    附录提交完成时刻（UTC）
 */
public record CorrigendumRecord(
        String corrigendumId,
        String observationId,
        String requestId,
        int corrigendumVersion,
        int previousVersion,
        int snapshotVersion,
        List<FieldChange> changes,
        String operator,
        Instant submittedAtUtc) {
}
