package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.StandardStatus;
import com.example.starter.calibration.model.StandardVersion;

/**
 * 标准器版本血缘持久化。血缘与窗口创建后不可变，仅状态可经失效激活变更；
 * 血缘创建、绑定标准器的测量提交与失效激活通过 lineage_lock 的 GLOBAL 行串行化。
 */
@Repository
public class StandardRepository {

    private static final String GLOBAL_LOCK_ID = "GLOBAL";

    private static final RowMapper<StandardVersion> MAPPER = (rs, rowNum) -> new StandardVersion(
            rs.getLong("id"),
            rs.getString("standard_id"),
            rs.getString("parent_standard_id"),
            JdbcTimes.fromDb(rs.getObject("valid_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("valid_to", LocalDateTime.class)),
            rs.getString("certificate_no"),
            StandardStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public StandardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取血缘全局互斥锁（须在事务内调用）：不存在锁行则插入，随后对该行 FOR UPDATE。
     */
    public void lockLineage() {
        try {
            jdbc.update("INSERT INTO lineage_lock (lock_id, created_at) VALUES (?, ?)",
                    GLOBAL_LOCK_ID, JdbcTimes.toDb(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在，直接进入行锁等待
        }
        jdbc.queryForObject("SELECT lock_id FROM lineage_lock WHERE lock_id = ? FOR UPDATE",
                String.class, GLOBAL_LOCK_ID);
    }

    /**
     * 插入标准器版本并返回生成的记录 ID；standard_id 冲突时抛出 DuplicateKeyException。
     */
    public long insert(String standardId, String parentStandardId, Instant validFrom, Instant validTo,
                       String certificateNo, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO standard_version "
                            + "(standard_id, parent_standard_id, valid_from, valid_to, certificate_no, "
                            + "status, version, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'VALID', 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, standardId);
            ps.setString(2, parentStandardId);
            ps.setObject(3, JdbcTimes.toDb(validFrom));
            ps.setObject(4, JdbcTimes.toDb(validTo));
            ps.setString(5, certificateNo);
            ps.setObject(6, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按业务键查询（不加锁）。
     */
    public Optional<StandardVersion> findByStandardId(String standardId) {
        return jdbc.query("SELECT * FROM standard_version WHERE standard_id = ?", MAPPER, standardId)
                .stream().findFirst();
    }

    /**
     * 按记录 ID 查询（不加锁）。
     */
    public Optional<StandardVersion> findById(long id) {
        return jdbc.query("SELECT * FROM standard_version WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询某版本的全部直接下级（按 standard_id 升序，保证闭包稳定排序）。
     */
    public List<StandardVersion> findChildren(String parentStandardId) {
        return jdbc.query("SELECT * FROM standard_version WHERE parent_standard_id = ? ORDER BY standard_id",
                MAPPER, parentStandardId);
    }

    /**
     * 查询全部标准器版本（按 standard_id 升序），用于闭包与最短路径计算。
     */
    public List<StandardVersion> findAll() {
        return jdbc.query("SELECT * FROM standard_version ORDER BY standard_id", MAPPER);
    }

    /**
     * 匹配测量时刻有效的标准器版本：VALID 且 valid_from &lt;= measuredAt &lt; valid_to。
     */
    public Optional<StandardVersion> findValidAt(String standardId, Instant measuredAt) {
        return jdbc.query(
                "SELECT * FROM standard_version "
                        + "WHERE standard_id = ? AND status = 'VALID' AND valid_from <= ? AND valid_to > ?",
                MAPPER, standardId, JdbcTimes.toDb(measuredAt), JdbcTimes.toDb(measuredAt))
                .stream().findFirst();
    }

    /**
     * 失效激活：将版本置为 INVALID 并递增版本号（须在持有血缘锁的事务内调用）。
     */
    public void markInvalid(long id) {
        jdbc.update("UPDATE standard_version SET status = 'INVALID', version = version + 1 WHERE id = ?", id);
    }
}
