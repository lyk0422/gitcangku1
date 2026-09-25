package com.example.starter.evidence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 双人复核封签记录表访问。记录只追加；(container_key, inspector_id, reviewer_id) 唯一，
 * 同一复核保管人对同一次 FAIL 巡检重复提交由唯一约束拒绝。
 */
@Repository
public class ContainerReviewRepository {

    private static final ReviewRowMapper ROW_MAPPER = new ReviewRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条复核记录；同一保管人重复复核时抛出 {@link DuplicateKeyException}。
     */
    public void insert(String containerKey, String inspectorId, String reviewerId, String note,
                       LocalDateTime reviewedAt, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_review
                            (container_key, inspector_id, reviewer_id, note, reviewed_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                containerKey, inspectorId, reviewerId, note, reviewedAt, now);
    }

    /**
     * 统计某次 FAIL 巡检已有多少名不同复核保管人（容器行已锁定时调用）。
     */
    public int countDistinctReviewers(String containerKey, String inspectorId) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(DISTINCT reviewer_id)
                        FROM container_review
                        WHERE container_key = ? AND inspector_id = ?
                        """,
                Integer.class, containerKey, inspectorId);
        return count == null ? 0 : count;
    }

    /**
     * 按容器查询全部复核记录（按发生顺序）。
     */
    public List<ContainerReview> findByContainer(String containerKey) {
        return jdbc.query(
                "SELECT * FROM container_review WHERE container_key = ? ORDER BY id",
                ROW_MAPPER, containerKey);
    }

    private static final class ReviewRowMapper implements RowMapper<ContainerReview> {
        @Override
        public ContainerReview mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerReview(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    rs.getString("inspector_id"),
                    rs.getString("reviewer_id"),
                    rs.getString("note"),
                    rs.getObject("reviewed_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
