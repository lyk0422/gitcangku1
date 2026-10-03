package com.example.starter.cue.repo;

import com.example.starter.cue.domain.CueItem;
import com.example.starter.cue.domain.CueVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 提示点版本与明细的 JDBC 仓库，全部使用参数绑定。
 */
@Repository
public class CueVersionRepository {

    private static final RowMapper<CueVersion> VERSION_MAPPER = (rs, n) -> new CueVersion(
            rs.getLong("id"), rs.getString("production"), rs.getInt("version_no"),
            rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<CueItem> ITEM_MAPPER = (rs, n) -> new CueItem(
            rs.getLong("id"), rs.getLong("version_id"), rs.getInt("seq"),
            rs.getString("cue_type"), rs.getString("trigger_phrase"),
            rs.getInt("duration_seconds"));

    private final JdbcTemplate jdbc;

    public CueVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建草稿版本，版本号取剧目内最大值加一，返回生成主键。 */
    public long insertVersion(String production, LocalDateTime createdAt) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM cue_version WHERE production = ?",
                Integer.class, production);
        int versionNo = max + 1;
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cue_version (production, version_no, status, created_at)"
                            + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, production);
            ps.setInt(2, versionNo);
            ps.setString(3, CueVersion.STATUS_DRAFT);
            ps.setTimestamp(4, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 批量插入版本提示点明细。 */
    public void insertItems(long versionId, List<CueItem> items) {
        jdbc.batchUpdate(
                "INSERT INTO cue_item (version_id, seq, cue_type, trigger_phrase, duration_seconds)"
                        + " VALUES (?, ?, ?, ?, ?)",
                items, items.size(), (ps, item) -> {
                    ps.setLong(1, versionId);
                    ps.setInt(2, item.seq());
                    ps.setString(3, item.cueType());
                    ps.setString(4, item.triggerPhrase());
                    ps.setInt(5, item.durationSeconds());
                });
    }

    /** 按 ID 查询版本。 */
    public Optional<CueVersion> findVersion(long versionId) {
        List<CueVersion> list = jdbc.query(
                "SELECT * FROM cue_version WHERE id = ?", VERSION_MAPPER, versionId);
        return list.stream().findFirst();
    }

    /** 查询剧目最新冻结版本。 */
    public Optional<CueVersion> findLatestFrozen(String production) {
        List<CueVersion> list = jdbc.query(
                "SELECT * FROM cue_version WHERE production = ? AND status = ?"
                        + " ORDER BY version_no DESC LIMIT 1",
                VERSION_MAPPER, production, CueVersion.STATUS_FROZEN);
        return list.stream().findFirst();
    }

    /** 按序号升序查询版本全部提示点。 */
    public List<CueItem> findItems(long versionId) {
        return jdbc.query("SELECT * FROM cue_item WHERE version_id = ? ORDER BY seq",
                ITEM_MAPPER, versionId);
    }

    /** 冻结版本（仅草稿可冻结），返回更新行数。 */
    public int markFrozen(long versionId) {
        return jdbc.update("UPDATE cue_version SET status = ? WHERE id = ? AND status = ?",
                CueVersion.STATUS_FROZEN, versionId, CueVersion.STATUS_DRAFT);
    }
}