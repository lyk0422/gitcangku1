package com.example.starter.calibration.repository;

import com.example.starter.calibration.domain.Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 校准证书持久化接口。证书创建后不可修改，只能撤销。
 */
public interface CertificateRepository {

    /**
     * 在同一事务内检查同仪器未撤销证书区间不重叠后插入新证书。
     * 实现必须保证并发调用时重叠证书最多一张成功（行锁/间隙锁或等效机制）。
     *
     * @return 持久化后的证书（含生成的 ID）
     * @throws com.example.starter.calibration.error.ApiException 区间重叠时抛出 409
     */
    Certificate insertIfNoOverlap(Certificate certificate);

    /**
     * 按 ID 查询证书。
     */
    Optional<Certificate> findById(long id);

    /**
     * 按 ID 查询并锁定证书行（SELECT ... FOR UPDATE），用于撤销与放行的串行化。
     */
    Optional<Certificate> findByIdForUpdate(long id);

    /**
     * 撤销证书；仅当存在且未撤销时生效。
     *
     * @return true 表示本次调用完成了撤销
     */
    boolean revoke(long id, Instant revokedAt);

    /**
     * 查找覆盖指定时刻的未撤销证书。区间两两不重叠，因此最多一张。
     */
    Optional<Certificate> findActiveCovering(String instrumentId, Instant instant);

    /**
     * 查询某仪器的全部证书（按有效起点升序）。
     */
    List<Certificate> findByInstrument(String instrumentId);
}
