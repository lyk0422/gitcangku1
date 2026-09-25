package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.CalcLogEntry;

/**
 * 计算指纹幂等日志持久化。同键并发由占位行 + 行锁串行化：
 * 先到事务插入占位行并持锁计算，成功提交时回填结果；失败回滚则占位行撤销（不占键）。
 */
@Repository
public class CalcLogRepository {

    private static final RowMapper<CalcLogEntry> MAPPER = (rs, rowNum) -> new CalcLogEntry(
            rs.getString("calc_key"),
            rs.getString("operation"),
            rs.getInt("http_status"),
            rs.getString("fingerprint"),
            rs.getString("result_json"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CalcLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试插入占位行（须在事务内调用）。插入成功表示当前事务获得该键的首次计算权；
     * 键已存在（含正在进行的并发事务）返回 false。
     */
    public boolean tryAcquire(String calcKey, String operation, Instant now) {
        try {
            jdbc.update("INSERT INTO calc_log (calc_key, operation, http_status, result_json, created_at) "
                            + "VALUES (?, ?, 0, NULL, ?)",
                    calcKey, operation, JdbcTimes.toDb(now));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * 按键查询并加行锁（须在事务内调用）：等待持有占位行的并发事务提交，
     * 随后读取其回填的首次结果用于重放。
     */
    public Optional<CalcLogEntry> findForUpdate(String calcKey) {
        return jdbc.query("SELECT * FROM calc_log WHERE calc_key = ? FOR UPDATE", MAPPER, calcKey)
                .stream().findFirst();
    }

    /**
     * 回填首次成功结果（须在持有该键占位行的事务内调用）。
     */
    public void complete(String calcKey, int httpStatus, String fingerprint, String resultJson) {
        jdbc.update("UPDATE calc_log SET http_status = ?, fingerprint = ?, result_json = ? WHERE calc_key = ?",
                httpStatus, fingerprint, resultJson, calcKey);
    }

    /**
     * 直接记录一条已成功操作的指纹与结果（用于由唯一约束/状态机裁决、不做重放的操作：
     * 提交、放行、驳回）。这些操作每种输入组合至多成功一次，故 calcKey 不会冲突。
     */
    public void insertCompleted(String calcKey, String operation, int httpStatus,
                                String fingerprint, String resultJson, Instant now) {
        jdbc.update("INSERT INTO calc_log (calc_key, operation, http_status, fingerprint, result_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                calcKey, operation, httpStatus, fingerprint, resultJson, JdbcTimes.toDb(now));
    }

    /**
     * 统计某操作类型的成功指纹条数（测试用于验证失败不占键）。
     */
    public int countByOperation(String operation) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calc_log WHERE operation = ?", Integer.class, operation);
        return count == null ? 0 : count;
    }

    /**
     * 判断某 calcKey 是否已被成功结果占用（http_status &gt; 0）。
     */
    public boolean existsCompleted(String calcKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calc_log WHERE calc_key = ? AND http_status > 0", Integer.class, calcKey);
        return count != null && count > 0;
    }
}
