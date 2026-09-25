package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.CalcRecord;

/**
 * calcKey 幂等记录持久化。仅业务成功事务写入（占用 calcKey）；
 * 业务校验失败（如 422/409 批次门禁）不写入，失败不占键，同键可重试成功。
 */
@Repository
public class CalcRecordRepository {

    private static final RowMapper<CalcRecord> MAPPER = (rs, rowNum) -> new CalcRecord(
            rs.getString("calc_key"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getString("response_json"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CalcRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 占用 calcKey 并固化首次成功响应；同键并发时后提交者抛 DuplicateKeyException（事务回滚，不占键）。
     */
    public void insert(CalcRecord record) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO calc_record (calc_key, operation, fingerprint, response_json, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, record.calcKey());
            ps.setString(2, record.operation());
            ps.setString(3, record.fingerprint());
            ps.setString(4, record.responseJson());
            ps.setObject(5, JdbcTimes.toDb(record.createdAt()));
            return ps;
        });
    }

    /**
     * 按 calcKey 查询已固化的成功记录。
     */
    public Optional<CalcRecord> findByKey(String calcKey) {
        return jdbc.query("SELECT * FROM calc_record WHERE calc_key = ?", MAPPER, calcKey)
                .stream().findFirst();
    }

    /**
     * 尝试占用：成功返回 true；calcKey 已被占用返回 false（不抛异常，便于在同事务内直接重放判定）。
     */
    public boolean tryInsert(CalcRecord record) {
        try {
            insert(record);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
