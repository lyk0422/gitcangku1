package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 证物介质表访问。介质状态变更必须先锁定证物行（与证物状态变更同事务），
 * 保证同一证物恰好一个 ACTIVE；RETIRED 介质仅作历史元数据保留。
 */
@Repository
public class EvidenceMediaRepository {

    private static final MediaRowMapper ROW_MAPPER = new MediaRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceMediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记新介质，初始版本 1；(evidence_key, media_no) 唯一，冲突由唯一约束拒绝。
     */
    public void insert(String evidenceKey, String mediaNo, String sha256, MediaStatus status,
                       String createdBy, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence_media
                            (evidence_key, media_no, sha256, status, version, created_by, created_at, retired_at)
                        VALUES (?, ?, ?, ?, 1, ?, ?, NULL)
                        """,
                evidenceKey, mediaNo, sha256, status.name(), createdBy, now);
    }

    /**
     * 按证物键与介质编号查询。
     */
    public Optional<EvidenceMedia> findByEvidenceKeyAndMediaNo(String evidenceKey, String mediaNo) {
        List<EvidenceMedia> rows = jdbc.query(
                "SELECT * FROM evidence_media WHERE evidence_key = ? AND media_no = ?",
                ROW_MAPPER, evidenceKey, mediaNo);
        return rows.stream().findFirst();
    }

    /**
     * 查询证物当前 ACTIVE 介质（当前读取来源；调用前必须已锁定证物行）。
     */
    public Optional<EvidenceMedia> findActiveByEvidenceKey(String evidenceKey) {
        List<EvidenceMedia> rows = jdbc.query(
                "SELECT * FROM evidence_media WHERE evidence_key = ? AND status = ?",
                ROW_MAPPER, evidenceKey, MediaStatus.ACTIVE.name());
        return rows.stream().findFirst();
    }

    /**
     * 按发生顺序查询证物全部介质（含 RETIRED 历史元数据）。
     */
    public List<EvidenceMedia> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM evidence_media WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 条件流转介质状态并递增版本；仅当当前状态匹配时生效。
     *
     * @return 是否成功流转（false 表示状态已被并发事务改变）
     */
    public boolean transition(long id, MediaStatus from, MediaStatus to, LocalDateTime retiredAt) {
        int updated = jdbc.update("""
                        UPDATE evidence_media
                        SET status = ?, version = version + 1, retired_at = ?
                        WHERE id = ? AND status = ?
                        """,
                to.name(), retiredAt, id, from.name());
        return updated == 1;
    }

    private static final class MediaRowMapper implements RowMapper<EvidenceMedia> {
        @Override
        public EvidenceMedia mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EvidenceMedia(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("media_no"),
                    rs.getString("sha256"),
                    MediaStatus.valueOf(rs.getString("status")),
                    rs.getInt("version"),
                    rs.getString("created_by"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("retired_at", LocalDateTime.class));
        }
    }
}
