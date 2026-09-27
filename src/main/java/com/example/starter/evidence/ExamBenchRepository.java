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
 * 检验台表访问。容量统计前必须先通过 {@link #findByKeyForUpdate} 锁定台位行，
 * 保证并发预约在同一检验台上按事务提交顺序串行裁决。
 */
@Repository
public class ExamBenchRepository {

    private static final ExamBenchRowMapper ROW_MAPPER = new ExamBenchRowMapper();

    private final JdbcTemplate jdbc;

    public ExamBenchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按业务键查询检验台（不加锁），用于只读诊断。
     */
    public Optional<ExamBench> findByKey(String benchKey) {
        List<ExamBench> rows = jdbc.query(
                "SELECT * FROM exam_bench WHERE bench_key = ?", ROW_MAPPER, benchKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定检验台行（SELECT ... FOR UPDATE）。
     */
    public Optional<ExamBench> findByKeyForUpdate(String benchKey) {
        List<ExamBench> rows = jdbc.query(
                "SELECT * FROM exam_bench WHERE bench_key = ? FOR UPDATE", ROW_MAPPER, benchKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部检验台（建档顺序），用于台位明细查询。
     */
    public List<ExamBench> findAll() {
        return jdbc.query("SELECT * FROM exam_bench ORDER BY id", ROW_MAPPER);
    }

    private static final class ExamBenchRowMapper implements RowMapper<ExamBench> {
        @Override
        public ExamBench mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExamBench(
                    rs.getLong("id"),
                    rs.getString("bench_key"),
                    rs.getString("bench_name"),
                    rs.getInt("capacity"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
