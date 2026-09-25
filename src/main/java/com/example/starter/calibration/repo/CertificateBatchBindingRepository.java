package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.CertificateBatchBinding;

/**
 * singleBatchOnly 证书的放行批次绑定持久化。证书一行最多一条绑定；
 * 首次放行引用时插入，后续其他批次引用即冲突。
 */
@Repository
public class CertificateBatchBindingRepository {

    private static final RowMapper<CertificateBatchBinding> MAPPER = (rs, rowNum) ->
            new CertificateBatchBinding(
                    rs.getLong("certificate_id"),
                    rs.getString("batch_id"),
                    JdbcTimes.fromDb(rs.getObject("bound_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CertificateBatchBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试把证书绑定到指定批次（须在持有证书行锁的事务内调用）。
     *
     * @return 绑定结果；若证书已被其他批次绑定，返回该已存在绑定而不覆盖
     */
    public Optional<CertificateBatchBinding> bindIfAbsent(long certificateId, String batchId, Instant boundAt) {
        Optional<CertificateBatchBinding> existing = findByCertificateId(certificateId);
        if (existing.isPresent()) {
            return existing;
        }
        try {
            jdbc.update("INSERT INTO certificate_batch_binding (certificate_id, batch_id, bound_at) "
                            + "VALUES (?, ?, ?)",
                    certificateId, batchId, JdbcTimes.toDb(boundAt));
        } catch (DuplicateKeyException ignored) {
            // 并发下已由其他事务绑定，重新读取其归属
            return findByCertificateId(certificateId);
        }
        return Optional.of(new CertificateBatchBinding(certificateId, batchId, boundAt));
    }

    /**
     * 查询证书的批次绑定。
     */
    public Optional<CertificateBatchBinding> findByCertificateId(long certificateId) {
        return jdbc.query(
                "SELECT * FROM certificate_batch_binding WHERE certificate_id = ?", MAPPER, certificateId)
                .stream().findFirst();
    }

    /**
     * 查询证书的批次绑定并加锁（须在持有证书行锁的事务内调用）。
     * 用当前读使并发放行的后到事务能看到先提交事务写入的绑定，避免 REPEATABLE READ 漏判。
     */
    public Optional<CertificateBatchBinding> findByCertificateIdForUpdate(long certificateId) {
        return jdbc.query(
                "SELECT * FROM certificate_batch_binding WHERE certificate_id = ? FOR UPDATE",
                MAPPER, certificateId).stream().findFirst();
    }

    /**
     * 查询某批次已绑定的全部证书（放行诊断用）。
     */
    public List<CertificateBatchBinding> findByBatchId(String batchId) {
        return jdbc.query(
                "SELECT * FROM certificate_batch_binding WHERE batch_id = ? ORDER BY certificate_id",
                MAPPER, batchId);
    }
}
