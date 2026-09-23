package com.example.starter.evidence.aliquot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 母样登记表访问。预留/释放/耗用均在持有母样行锁（SELECT ... FOR UPDATE）后进行，
 * 由条件更新保证数量恒等式（total = available + reserved + consumed）不被并发打破。
 */
@Repository
public class MotherSampleRepository {

    private static final MotherSampleRowMapper ROW_MAPPER = new MotherSampleRowMapper();

    private final JdbcTemplate jdbc;

    public MotherSampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记母样；总量单位首次写入后不再提供更新入口，重复键由唯一约束拒绝。
     */
    public void insert(String sampleKey, long totalQty, String unit, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO mother_sample
                            (sample_key, total_qty, unit, reserved_qty, consumed_qty, version,
                             created_at, updated_at)
                        VALUES (?, ?, ?, 0, 0, 0, ?, ?)
                        """,
                sampleKey, totalQty, unit, now, now);
    }

    /**
     * 按母样键查询（不加锁），用于只读查询。
     */
    public Optional<MotherSample> findByKey(String sampleKey) {
        List<MotherSample> rows = jdbc.query(
                "SELECT * FROM mother_sample WHERE sample_key = ?", ROW_MAPPER, sampleKey);
        return rows.stream().findFirst();
    }

    /**
     * 按母样键查询并锁定行（SELECT ... FOR UPDATE），用于预留/释放/耗用与版本比对。
     */
    public Optional<MotherSample> findByKeyForUpdate(String sampleKey) {
        List<MotherSample> rows = jdbc.query(
                "SELECT * FROM mother_sample WHERE sample_key = ? FOR UPDATE", ROW_MAPPER, sampleKey);
        return rows.stream().findFirst();
    }

    /**
     * 原子预留：仅当可用余额（total - reserved - consumed）足够时，
     * reserved_qty 增加且 version 加 1。并发重叠申请由行锁串行化，不会超额预留。
     *
     * @return 是否预留成功（false 表示余额不足，调用方须整单回滚且不占键）
     */
    public boolean reserve(String sampleKey, long qty, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE mother_sample
                        SET reserved_qty = reserved_qty + ?, version = version + 1, updated_at = ?
                        WHERE sample_key = ?
                          AND reserved_qty + consumed_qty + ? <= total_qty
                        """,
                qty, now, sampleKey, qty);
        return updated == 1;
    }

    /**
     * 释放预留（拒绝/审核前取消）：reserved_qty 减少，version 加 1。
     * 仅在预留数量充足时生效，恒等式不被破坏。
     *
     * @return 是否释放成功（false 表示预留数量与请求不一致）
     */
    public boolean release(String sampleKey, long qty, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE mother_sample
                        SET reserved_qty = reserved_qty - ?, version = version + 1, updated_at = ?
                        WHERE sample_key = ? AND reserved_qty >= ?
                        """,
                qty, now, sampleKey, qty);
        return updated == 1;
    }

    /**
     * 预留转耗用（二次确认成功）：reserved_qty 减少、consumed_qty 增加，version 加 1。
     * 全部母样在同一事务内一次完成，要么全部耗用要么全部回滚，不产生半生成。
     *
     * @return 是否耗用成功（false 表示预留数量与请求不一致）
     */
    public boolean consume(String sampleKey, long qty, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE mother_sample
                        SET reserved_qty = reserved_qty - ?,
                            consumed_qty = consumed_qty + ?,
                            version = version + 1,
                            updated_at = ?
                        WHERE sample_key = ? AND reserved_qty >= ?
                        """,
                qty, qty, now, sampleKey, qty);
        return updated == 1;
    }

    private static final class MotherSampleRowMapper implements RowMapper<MotherSample> {
        @Override
        public MotherSample mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MotherSample(
                    rs.getLong("id"),
                    rs.getString("sample_key"),
                    rs.getLong("total_qty"),
                    rs.getString("unit"),
                    rs.getLong("reserved_qty"),
                    rs.getLong("consumed_qty"),
                    rs.getLong("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
