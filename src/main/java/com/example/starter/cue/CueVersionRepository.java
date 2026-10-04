package com.example.starter.cue;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.cue.CueDtos.CueInput;
import com.example.starter.cue.CueDtos.CueView;

/**
 * 提示点编排版本与提示点的持久化访问。
 */
@Repository
public class CueVersionRepository {

    /** 编排版本行；frozenAt未冻结时为null。 */
    public record VersionRow(long id, String playCode, int versionNo, String status,
                             Instant createdAt, Instant frozenAt) {
    }

    private static final RowMapper<VersionRow> VERSION_MAPPER = (rs, n) -> new VersionRow(
            rs.getLong("id"),
            rs.getString("play_code"),
            rs.getInt("version_no"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("frozen_at")));

    private static final RowMapper<CueView> CUE_MAPPER = (rs, n) -> new CueView(
            rs.getInt("seq_no"),
            rs.getString("cue_type"),
            rs.getString("trigger_phrase"),
            rs.getInt("duration_seconds"));

    private final JdbcTemplate jdbc;

    public CueVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int nextVersionNo(String playCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM cue_version WHERE play_code = ?",
                Integer.class, playCode);
        return max == null ? 1 : max + 1;
    }

    public long insertVersion(String playCode, int versionNo, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cue_version (play_code, version_no, status, created_at) VALUES (?, ?, 'DRAFT', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, playCode);
            ps.setInt(2, versionNo);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("未能获取新版本主键");
        }
        return key.longValue();
    }

    public void insertItem(long versionId, CueInput cue) {
        jdbc.update("INSERT INTO cue_item (version_id, seq_no, cue_type, trigger_phrase, duration_seconds)"
                        + " VALUES (?, ?, ?, ?, ?)",
                versionId, cue.seqNo(), cue.cueType(), cue.triggerPhrase(), cue.durationSeconds());
    }

    public Optional<VersionRow> findVersion(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM cue_version WHERE id = ?", VERSION_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<CueView> findItems(long versionId) {
        return jdbc.query("SELECT * FROM cue_item WHERE version_id = ? ORDER BY seq_no",
                CUE_MAPPER, versionId);
    }

    public Optional<VersionRow> findLatestFrozen(String playCode) {
        List<VersionRow> rows = jdbc.query(
                "SELECT * FROM cue_version WHERE play_code = ? AND status = 'FROZEN'"
                        + " ORDER BY version_no DESC LIMIT 1",
                VERSION_MAPPER, playCode);
        return rows.stream().findFirst();
    }

    public void markFrozen(long id, Instant frozenAt) {
        jdbc.update("UPDATE cue_version SET status = 'FROZEN', frozen_at = ? WHERE id = ?",
                Timestamp.from(frozenAt), id);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}