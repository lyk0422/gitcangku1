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
 * 检验台表访问。容量校验前必须通过 {@link #findByKeyForUpdate} 锁定检验台行，
 * 保证并发预约按事务提交顺序裁决，不会超容量。
 */
@Repository
public class ExamBenchRepository {

    private static final BenchRowMapper ROW_MAPPER = new BenchRowMapper();

    private final JdbcTemplate jdbc;

    public ExamBenchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 注册检验台；bench_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String benchKey, int capacity, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO exam_bench (bench_key, capacity, created_at)
                        VALUES (?, ?, ?)
                        """,
                benchKey, capacity, now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景。
     */
    public Optional<ExamBench> findByKey(String benchKey) {
        List<ExamBench> rows = jdbc.query(
                "SELECT * FROM exam_bench WHERE bench_key = ?", ROW_MAPPER, benchKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定检验台行（SELECT ... FOR UPDATE），用于预约容量裁决。
     */
    public Optional<ExamBench> findByKeyForUpdate(String benchKey) {
        List<ExamBench> rows = jdbc.query(
                "SELECT * FROM exam_bench WHERE bench_key = ? FOR UPDATE", ROW_MAPPER, benchKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部检验台（按注册顺序）。
     */
    public List<ExamBench> findAll() {
        return jdbc.query("SELECT * FROM exam_bench ORDER BY id", ROW_MAPPER);
    }

    private static final class BenchRowMapper implements RowMapper<ExamBench> {
        @Override
        public ExamBench mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExamBench(
                    rs.getLong("id"),
                    rs.getString("bench_key"),
                    rs.getInt("capacity"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
