package com.example.starter.observation;

import java.time.Instant;

/**
 * 分钟观测记录快照：对应 minute_observation_current 当前状态或 minute_observation_version 历史版本的一行。
 *
 * @param observationId 分钟观测记录唯一标识
 * @param version       版本号，从 1 开始
 * @param stationId     站号（可编辑字段，参与三方合并）；删除墓碑版本为 null
 * @param minuteUtc     观测时刻（UTC 整分钟，可编辑字段，参与三方合并）；删除墓碑版本为 null
 * @param reading       观测读数，十进制字符串，最多三位小数，比较按数值；删除墓碑版本为 null
 * @param deleted       是否为删除墓碑：true 时业务字段无意义
 */
public record MinuteObservationSnapshot(
        String observationId,
        int version,
        String stationId,
        Instant minuteUtc,
        String reading,
        boolean deleted) {
}
