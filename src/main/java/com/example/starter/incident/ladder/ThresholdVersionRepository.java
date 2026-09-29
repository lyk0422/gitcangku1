package com.example.starter.incident.ladder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 阈值版本与当前版本指针仓储。
 * 版本行只插入不更新（不可变）；发布新版本时以单行指针表 ladder_current_version 原子切换。
 */
@Repository
public class ThresholdVersionRepository {

    /** 当前版本指针行的固定主键。 */
    private static final int CURRENT_ROW_ID = 1;

    private final JdbcTemplate jdbc;

    public ThresholdVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ThresholdVersion> MAPPER = (rs, n) -> map(rs);

    private static ThresholdVersion map(ResultSet rs) throws SQLException {
        return new ThresholdVersion(
                rs.getLong("id"), rs.getString("version_key"),
                List.of(rs.getLong("threshold_1"), rs.getLong("threshold_2"),
                        rs.getLong("threshold_3"), rs.getLong("threshold_4"),
                        rs.getLong("threshold_5")),
                rs.getInt("cooldown_minutes"), rs.getString("note"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入不可变阈值版本行，返回生成主键；versionKey 重复由唯一约束拒绝。
     */
    public long insert(ThresholdVersion version) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO escalation_threshold_versions (version_key, threshold_1, threshold_2,"
                            + " threshold_3, threshold_4, threshold_5, cooldown_minutes, note,"
                            + " created_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, version.versionKey());
            for (int i = 0; i < ThresholdVersion.LEVELS; i++) {
                ps.setLong(2 + i, version.thresholds().get(i));
            }
            ps.setInt(7, version.cooldownMinutes());
            ps.setString(8, version.note());
            ps.setString(9, version.createdBy());
            ps.setTimestamp(10, Timestamp.from(version.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询版本（不加锁）。
     */
    public Optional<ThresholdVersion> findByKey(String versionKey) {
        List<ThresholdVersion> rows = jdbc.query(
                "SELECT * FROM escalation_threshold_versions WHERE version_key = ?",
                MAPPER, versionKey);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询版本。
     */
    public Optional<ThresholdVersion> findById(long id) {
        List<ThresholdVersion> rows = jdbc.query(
                "SELECT * FROM escalation_threshold_versions WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部版本，按发布顺序（id 升序）返回。
     */
    public List<ThresholdVersion> listAll() {
        return jdbc.query(
                "SELECT * FROM escalation_threshold_versions ORDER BY id", MAPPER);
    }

    /**
     * 锁定读取当前生效版本（先锁指针行再读版本行）；无指针行时返回空。
     */
    public Optional<ThresholdVersion> lockCurrent() {
        List<Long> ids = jdbc.query(
                "SELECT threshold_version_id FROM ladder_current_version WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getLong(1), CURRENT_ROW_ID);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return findById(ids.get(0));
    }

    /**
     * 普通读取当前生效版本，用于只读诊断与明细查询。
     */
    public Optional<ThresholdVersion> current() {
        List<Long> ids = jdbc.query(
                "SELECT threshold_version_id FROM ladder_current_version WHERE id = ?",
                (rs, n) -> rs.getLong(1), CURRENT_ROW_ID);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return findById(ids.get(0));
    }

    /**
     * 将当前版本指针指向新发布版本（行不存在则插入固定主键行）。
     */
    public void pointCurrent(long thresholdVersionId, Instant now) {
        int updated = jdbc.update("UPDATE ladder_current_version SET threshold_version_id = ?,"
                + " updated_at = ? WHERE id = ?", thresholdVersionId, Timestamp.from(now), CURRENT_ROW_ID);
        if (updated == 0) {
            jdbc.update("INSERT INTO ladder_current_version (id, threshold_version_id, updated_at)"
                    + " VALUES (?,?,?)", CURRENT_ROW_ID, thresholdVersionId, Timestamp.from(now));
        }
    }
}
