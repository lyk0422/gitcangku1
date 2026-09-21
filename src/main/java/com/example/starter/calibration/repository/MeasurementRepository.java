package com.example.starter.calibration.repository;

import com.example.starter.calibration.domain.Measurement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 测量记录持久化接口。
 */
public interface MeasurementRepository {

    /**
     * 插入测量记录。measurementKey 重复时抛出 409。
     *
     * @return 持久化后的测量（含生成的 ID）
     */
    Measurement insert(Measurement measurement);

    /**
     * 按 ID 查询测量。
     */
    Optional<Measurement> findById(long id);

    /**
     * 按 ID 查询并锁定测量行（SELECT ... FOR UPDATE）。
     */
    Optional<Measurement> findByIdForUpdate(long id);

    /**
     * 按业务幂等键查询。
     */
    Optional<Measurement> findByMeasurementKey(String measurementKey);

    /**
     * 仅当测量仍处于待放行状态时将其置为已放行（条件更新，保证并发安全）。
     *
     * @return true 表示本次调用完成了状态迁移
     */
    boolean markReleased(long id, String releasedBy, Instant releasedAt);

    /**
     * 按 ID 集合批量查询。
     */
    List<Measurement> findByIds(Collection<Long> ids);

    /**
     * 查询当前可用结果：已放行且其证书未被撤销。instrumentId 为 null 时不过滤。
     */
    List<Measurement> findCurrentUsable(String instrumentId);
}
