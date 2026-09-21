package com.example.starter.calibration.repository;

import com.example.starter.calibration.domain.ReleaseRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 放行历史持久化接口。记录一经写入永久保留。
 */
public interface ReleaseRecordRepository {

    /**
     * 批量插入放行记录（同一批次）。
     *
     * @return 持久化后的记录（含生成的 ID）
     */
    List<ReleaseRecord> insertAll(List<ReleaseRecord> records);

    /**
     * 按测量 ID 查询放行记录（一次测量最多一条）。
     */
    Optional<ReleaseRecord> findByMeasurementId(long measurementId);

    /**
     * 按测量 ID 集合批量查询放行记录。
     */
    List<ReleaseRecord> findByMeasurementIds(Collection<Long> measurementIds);
}
