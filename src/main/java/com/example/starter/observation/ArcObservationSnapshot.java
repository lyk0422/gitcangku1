package com.example.starter.observation;

import java.util.List;

/**
 * 圆弧观测记录快照：对应 arc_observation_current 当前状态或某历史版本的完整内容。
 *
 * @param observationId   圆弧观测记录唯一标识
 * @param version         版本号，从 1 开始
 * @param deleted         是否为删除墓碑：true 时 arcs 为空
 * @param conflictPending 是否存在未解决冲突：true 时禁止参与裁定，人工裁决后清除
 * @param arcs            该版本全部圆弧分量，按起始角升序；墓碑版本为空列表
 */
public record ArcObservationSnapshot(
        String observationId,
        int version,
        boolean deleted,
        boolean conflictPending,
        List<ArcComponent> arcs) {
}
