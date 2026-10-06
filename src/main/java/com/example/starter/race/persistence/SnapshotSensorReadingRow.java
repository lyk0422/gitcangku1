package com.example.starter.race.persistence;

/**
 * result_snapshot_sensor_reading 表行记录：封榜时冻结的三源读数依据。
 *
 * @param raceId         所属快照的赛事ID
 * @param bib            参赛号
 * @param checkpointCode 三源检查点代码
 * @param sensorId       传感器ID
 * @param revision       封榜时冻结的读数修订号
 * @param elapsedMillis  封榜时冻结的该传感器累计耗时（毫秒）
 * @param adjudicationId 冻结依据的有效裁定ID
 */
public record SnapshotSensorReadingRow(
        String raceId,
        String bib,
        String checkpointCode,
        String sensorId,
        int revision,
        long elapsedMillis,
        String adjudicationId
) {
}
