package com.example.starter.cue;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 编排版本与提示点的数据访问。 */
@Repository
public class CueRepository {

    private static final RowMapper<CueVersion> VERSION_MAPPER = (rs, n) -> new CueVersion(
            rs.getLong("id"),
            VersionStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<CueItem> ITEM_MAPPER = (rs, n) -> new CueItem(
            rs.getLong("id"),
            rs.getLong("version_id"),
            rs.getInt("seq"),
            CueType.valueOf(rs.getString("cue_type")),
            rs.getString("trigger_phrase"),
            rs.getInt("duration_seconds"));

    private final JdbcTemplate jdbc;

    public CueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建草稿版本并返回自增ID。 */
    public long insertVersion() {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cue_version (status) VALUES ('DRAFT')",
                    new String[] {"id"});
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 在版本下追加一条提示点，创建时不做业务校验。 */
    public void insertItem(long versionId, CueDtos.CueInput cue) {
        jdbc.update("INSERT INTO cue_item (version_id, seq, cue_type, trigger_phrase, duration_seconds)"
                        + " VALUES (?, ?, ?, ?, ?)",
                versionId, cue.seq(), cue.cueType().name(), cue.triggerPhrase(), cue.durationSeconds());
    }

    public Optional<CueVersion> findVersion(long id) {
        List<CueVersion> rows = jdbc.query("SELECT * FROM cue_version WHERE id = ?",
                VERSION_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 最新版本ID，用于未开始场次重新绑定。 */
    public Optional<Long> findLatestVersionId() {
        List<Long> rows = jdbc.query("SELECT MAX(id) FROM cue_version",
                (rs, n) -> (Long) rs.getObject(1));
        return Optional.ofNullable(rows.get(0));
    }

    /** 按序号升序读取版本全部提示点。 */
    public List<CueItem> findItems(long versionId) {
        return jdbc.query("SELECT * FROM cue_item WHERE version_id = ? ORDER BY seq",
                ITEM_MAPPER, versionId);
    }

    /** 将版本标记为已冻结。 */
    public void markFrozen(long versionId) {
        jdbc.update("UPDATE cue_version SET status = 'FROZEN' WHERE id = ?", versionId);
    }

    /** 供测试核对冻结状态写入时间。 */
    public void touchCreatedAt(long versionId, Timestamp ts) {
        jdbc.update("UPDATE cue_version SET created_at = ? WHERE id = ?", ts, versionId);
    }
}