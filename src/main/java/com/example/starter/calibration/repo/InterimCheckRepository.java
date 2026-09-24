package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.CheckVerdict;
import com.example.starter.calibration.model.InterimCheck;

/**
 * 期间核查记录持久化。核查记录只增不改不删；check_key、(instrument_id, checked_at)、
 * request_id 均有唯一约束，冲突由数据库裁决。
 */
@Repository
public class InterimCheckRepository {

    private static final String COLUMNS =
            "id, check_key, instrument_id, checked_at, standard_value, measured_value, tolerance, "
                    + "verdict, checked_by, request_id, created_at";

    private static final RowMapper<InterimCheck> MAPPER = (rs, rowNum) -> new InterimCheck(
            rs.getLong("id"),
            rs.getString("check_key"),
            rs.getString("instrument_id"),
            JdbcTimes.fromDb(rs.getObject("checked_at", LocalDateTime.class)),
            rs.getBigDecimal("standard_value"),
            rs.getBigDecimal("measured_value"),
            rs.getBigDecimal("tolerance"),
            CheckVerdict.valueOf(rs.getString("verdict")),
            rs.getString("checked_by"),
            rs.getString("request_id"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<RequestRecord> REQUEST_MAPPER = (rs, rowNum) -> new RequestRecord(
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getString("check_key"));

    private final JdbcTemplate jdbc;

    public InterimCheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入核查记录并返回生成的 ID；唯一约束冲突抛 DuplicateKeyException。 */
    public long insert(InterimCheck check) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO interim_check "
                            + "(check_key, instrument_id, checked_at, standard_value, measured_value, "
                            + "tolerance, verdict, checked_by, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, check.checkKey());
            ps.setString(2, check.instrumentId());
            ps.setObject(3, JdbcTimes.toDb(check.checkedAt()));
            ps.setBigDecimal(4, check.standardValue());
            ps.setBigDecimal(5, check.measuredValue());
            ps.setBigDecimal(6, check.tolerance());
            ps.setString(7, check.verdict().name());
            ps.setString(8, check.checkedBy());
            ps.setString(9, check.requestId());
            ps.setObject(10, JdbcTimes.toDb(check.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按核查键查询。 */
    public Optional<InterimCheck> findByKey(String checkKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM interim_check WHERE check_key = ?",
                MAPPER, checkKey).stream().findFirst();
    }

    /** 按核查键查询并对记录加行锁（须在事务内调用）。 */
    public Optional<InterimCheck> findByKeyForUpdate(String checkKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM interim_check WHERE check_key = ? FOR UPDATE",
                MAPPER, checkKey).stream().findFirst();
    }

    /** 按主键查询。 */
    public Optional<InterimCheck> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM interim_check WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按主键查询并加行锁（须在事务内调用）。 */
    public Optional<InterimCheck> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM interim_check WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 该仪器严格早于 before 时刻的最近一条 PASS 核查（按核查时刻降序、ID 降序）。
     */
    public Optional<InterimCheck> findLatestPassBefore(String instrumentId, Instant before) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM interim_check "
                        + "WHERE instrument_id = ? AND verdict = 'PASS' AND checked_at < ? "
                        + "ORDER BY checked_at DESC, id DESC LIMIT 1",
                MAPPER, instrumentId, JdbcTimes.toDb(before)).stream().findFirst();
    }

    /** 该仪器指定核查时刻是否已有核查记录（同一仪器同一时刻只允许一条）。 */
    public Optional<InterimCheck> findByInstrumentAndTime(String instrumentId, Instant checkedAt) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM interim_check WHERE instrument_id = ? AND checked_at = ?",
                MAPPER, instrumentId, JdbcTimes.toDb(checkedAt)).stream().findFirst();
    }

    /** 核查历史：可按仪器过滤，按核查时刻升序、ID 升序。 */
    public List<InterimCheck> findHistory(String instrumentId) {
        if (instrumentId == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM interim_check ORDER BY checked_at, id",
                    MAPPER);
        }
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM interim_check WHERE instrument_id = ? "
                        + "ORDER BY checked_at, id",
                MAPPER, instrumentId);
    }

    /** 登记写操作幂等请求（须在业务事务内调用，失败回滚即不占键）。 */
    public void insertRequest(String requestId, String requestHash, String checkKey,
                              Instant createdAt) {
        jdbc.update("INSERT INTO check_request (request_id, request_hash, check_key, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                requestId, requestHash, checkKey, JdbcTimes.toDb(createdAt));
    }

    /** 查询已生效的幂等请求登记。 */
    public Optional<RequestRecord> findRequest(String requestId) {
        return jdbc.query("SELECT request_id, request_hash, check_key FROM check_request "
                        + "WHERE request_id = ?", REQUEST_MAPPER, requestId)
                .stream().findFirst();
    }

    /** 对幂等请求行加行锁；不存在时返回空。 */
    public Optional<RequestRecord> findRequestForUpdate(String requestId) {
        return jdbc.query("SELECT request_id, request_hash, check_key FROM check_request "
                        + "WHERE request_id = ? FOR UPDATE", REQUEST_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 幂等请求登记记录。
     *
     * @param requestId   请求幂等键
     * @param requestHash 归一化参数摘要
     * @param checkKey    首次生效产生的核查键
     */
    public record RequestRecord(String requestId, String requestHash, String checkKey) {
    }
}
