package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 检验台表访问。容量校验事务内先通过 {@link #findByKeyForUpdate} 锁定检验台行，
 * 使同一检验台上的并发预约按事务提交顺序串行裁决。
 */
@Repository
public class LabBenchRepository {

    private static final BenchRowMapper ROW_MAPPER = new BenchRowMapper();

    private final JdbcTemplate jdbc;

    public LabBenchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 注册检验台；bench_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String benchKey, String name, int capacity, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO lab_bench (bench_key, name, capacity, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                benchKey, name, capacity, now);
    }

    /**
     * 按业务键查询检验台（不加锁）。
     */
    public Optional<LabBench> findByKey(String benchKey) {
        List<LabBench> rows = jdbc.query(
                "SELECT * FROM lab_bench WHERE bench_key = ?", ROW_MAPPER, benchKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定检验台行（SELECT ... FOR UPDATE），用于预约容量裁决。
     */
    public Optional<LabBench> findByKeyForUpdate(String benchKey) {
        List<LabBench> rows = jdbc.query(
                "SELECT * FROM lab_bench WHERE bench_key = ? FOR UPDATE", ROW_MAPPER, benchKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部检验台（按注册顺序）。
     */
    public List<LabBench> findAll() {
        return jdbc.query("SELECT * FROM lab_bench ORDER BY id", ROW_MAPPER);
    }

    private static final class BenchRowMapper implements RowMapper<LabBench> {
        @Override
        public LabBench mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LabBench(
                    rs.getLong("id"),
                    rs.getString("bench_key"),
                    rs.getString("name"),
                    rs.getInt("capacity"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
