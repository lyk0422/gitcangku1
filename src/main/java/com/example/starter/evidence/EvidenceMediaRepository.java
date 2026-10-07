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
 * 证物介质表访问。介质编号与摘要入库后不可修改，仅状态随迁移流转；
 * 状态变更必须先通过证物行锁（EvidenceRepository.findByKeyForUpdate）串行化，
 * 保证同一证物恰好一个 ACTIVE。
 */
@Repository
public class EvidenceMediaRepository {

    private static final MediaRowMapper ROW_MAPPER = new MediaRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceMediaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记介质；(evidence_key, media_no) 唯一，冲突由唯一约束拒绝。
     */
    public void insert(String evidenceKey, String mediaNo, String sha256, MediaStatus status,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence_media
                            (evidence_key, media_no, sha256, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, mediaNo, sha256, status.name(), now, now);
    }

    /**
     * 按证物查询全部介质（按登记顺序），含 RETIRED 历史。
     */
    public List<EvidenceMedia> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM evidence_media WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
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
     * 按证物与介质编号查询。
     */
    public Optional<EvidenceMedia> findByEvidenceKeyAndMediaNo(String evidenceKey, String mediaNo) {
        List<EvidenceMedia> rows = jdbc.query(
                "SELECT * FROM evidence_media WHERE evidence_key = ? AND media_no = ?",
                ROW_MAPPER, evidenceKey, mediaNo);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询。
     */
    public Optional<EvidenceMedia> findById(long id) {
        List<EvidenceMedia> rows = jdbc.query(
                "SELECT * FROM evidence_media WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 仅更新介质状态（切换时新介质转 ACTIVE、旧介质转 RETIRED）。
     */
    public void updateStatus(long id, MediaStatus status, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence_media SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), now, id);
    }

    /**
     * 验证通过：写入已确认摘要并转为 VERIFIED。
     */
    public void markVerified(long id, String verifiedSha256, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence_media SET status = ?, sha256 = ?, updated_at = ? WHERE id = ?",
                MediaStatus.VERIFIED.name(), verifiedSha256, now, id);
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
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
