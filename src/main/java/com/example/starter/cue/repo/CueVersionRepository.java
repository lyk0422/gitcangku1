package com.example.starter.cue.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.cue.domain.CueItem;
import com.example.starter.cue.domain.CueVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 编排版本与版本内提示点的数据访问；版本一经创建不可变。
 */
@Repository
public class CueVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public CueVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入新版本并返回自增主键。
     */
    public long insertVersion(String playCode, int versionNo, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO cue_version (play_code, version_no, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, playCode);
            ps.setInt(2, versionNo);
            ps.setTimestamp(3, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 追加一个版本内提示点。
     */
    public void insertItem(long versionId, int seqNo, String cueType, String triggerWord, int durationSeconds) {
        jdbcTemplate.update(
                "INSERT INTO cue_item (version_id, seq_no, cue_type, trigger_word, duration_seconds) "
                        + "VALUES (?, ?, ?, ?, ?)",
                versionId, seqNo, cueType, triggerWord, durationSeconds);
    }

    /**
     * 计算剧目下一版本号（当前最大版本号 + 1，无版本时为 1）；并发创建由唯一约束兜底。
     */
    public int nextVersionNo(String playCode) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM cue_version WHERE play_code = ?",
                Integer.class, playCode);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 按剧目与版本号查询版本，不存在返回 null。
     */
    public CueVersion find(String playCode, int versionNo) {
        List<CueVersion> list = jdbcTemplate.query(
                "SELECT id, play_code, version_no, created_at FROM cue_version "
                        + "WHERE play_code = ? AND version_no = ?",
                (rs, rowNum) -> new CueVersion(
                        rs.getLong("id"),
                        rs.getString("play_code"),
                        rs.getInt("version_no"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                playCode, versionNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按主键查询版本，不存在返回 null。
     */
    public CueVersion findById(long versionId) {
        List<CueVersion> list = jdbcTemplate.query(
                "SELECT id, play_code, version_no, created_at FROM cue_version WHERE id = ?",
                (rs, rowNum) -> new CueVersion(
                        rs.getLong("id"),
                        rs.getString("play_code"),
                        rs.getInt("version_no"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                versionId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询剧目最新版本，不存在返回 null。
     */
    public CueVersion findLatest(String playCode) {
        List<CueVersion> list = jdbcTemplate.query(
                "SELECT id, play_code, version_no, created_at FROM cue_version "
                        + "WHERE play_code = ? ORDER BY version_no DESC LIMIT 1",
                (rs, rowNum) -> new CueVersion(
                        rs.getLong("id"),
                        rs.getString("play_code"),
                        rs.getInt("version_no"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                playCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 查询版本内全部提示点，按序号升序。
     */
    public List<CueItem> findItems(long versionId) {
        return jdbcTemplate.query(
                "SELECT id, version_id, seq_no, cue_type, trigger_word, duration_seconds "
                        + "FROM cue_item WHERE version_id = ? ORDER BY seq_no ASC",
                (rs, rowNum) -> new CueItem(
                        rs.getLong("id"),
                        rs.getLong("version_id"),
                        rs.getInt("seq_no"),
                        rs.getString("cue_type"),
                        rs.getString("trigger_word"),
                        rs.getInt("duration_seconds")),
                versionId);
    }
}
