package com.example.starter.incident;

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
 * 升级阶梯阈值版本仓储。版本落库后不可变；versionKey 全局唯一。
 * 后续版本仅被新的分数更新引用，不回写事件已有等级与历史。
 */
@Repository
public class LadderVersionRepository {

    private final JdbcTemplate jdbc;

    public LadderVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LadderVersion> MAPPER = (rs, n) -> map(rs);

    private static LadderVersion map(ResultSet rs) throws SQLException {
        return new LadderVersion(
                rs.getLong("id"), rs.getString("version_key"),
                rs.getInt("threshold_1"), rs.getInt("threshold_2"), rs.getInt("threshold_3"),
                rs.getInt("threshold_4"), rs.getInt("threshold_5"),
                rs.getInt("cooldown_minutes"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入不可变阈值版本，返回生成主键。versionKey 唯一约束兜底并发重复创建。
     */
    public long insert(LadderVersion version) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO ladder_versions (version_key, threshold_1, threshold_2, threshold_3,"
                            + " threshold_4, threshold_5, cooldown_minutes, created_by, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, version.versionKey());
            ps.setInt(2, version.threshold1());
            ps.setInt(3, version.threshold2());
            ps.setInt(4, version.threshold3());
            ps.setInt(5, version.threshold4());
            ps.setInt(6, version.threshold5());
            ps.setInt(7, version.cooldownMinutes());
            ps.setString(8, version.createdBy());
            ps.setTimestamp(9, Timestamp.from(version.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按版本键查询（不加锁），用于只读场景。
     */
    public Optional<LadderVersion> findByKey(String versionKey) {
        List<LadderVersion> rows = jdbc.query(
                "SELECT * FROM ladder_versions WHERE version_key = ?", MAPPER, versionKey);
        return rows.stream().findFirst();
    }

    /**
     * 按版本键查询并锁定行（SELECT ... FOR UPDATE），用于创建版本的并发唯一串行化。
     */
    public Optional<LadderVersion> lockByKey(String versionKey) {
        List<LadderVersion> rows = jdbc.query(
                "SELECT * FROM ladder_versions WHERE version_key = ? FOR UPDATE", MAPPER, versionKey);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询阈值版本（历史条目回查版本键用）。
     */
    public Optional<LadderVersion> findById(long id) {
        List<LadderVersion> rows = jdbc.query(
                "SELECT * FROM ladder_versions WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询全部阈值版本，按创建顺序返回（只读诊断）。
     */
    public List<LadderVersion> listAll() {
        return jdbc.query("SELECT * FROM ladder_versions ORDER BY id", MAPPER);
    }
}
