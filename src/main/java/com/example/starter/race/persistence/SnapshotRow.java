package com.example.starter.race.persistence;

import java.util.List;

/**
 * result_snapshot 头表与其条目合成的封榜快照。
 *
 * @param raceId  赛事ID
 * @param version 封榜时的版本号
 * @param sealedAt 封榜时间，Unix毫秒时间戳
 * @param entries 按展示顺序排列的快照条目
 */
public record SnapshotRow(String raceId, int version, long sealedAt, List<SnapshotEntryRow> entries) {
}
