package com.example.starter.container;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 容器复核封签记录表访问。(container_id, custodian_id) 唯一，记录只追加。
 */
@Repository
public class ContainerReviewRepository {

    private static final ReviewRowMapper ROW_MAPPER = new ReviewRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条复核记录；同一保管人重复复核时唯一约束冲突。
     */
    public void insert(String containerId, String custodianId, String note, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_review (container_id, custodian_id, note, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                containerId, custodianId, note, now);
    }

    /**
     * 查询某保管人是否已对该容器复核（调用前须锁定容器行）。
     */
    public Optional<ContainerReview> find(String containerId, String custodianId) {
        List<ContainerReview> rows = jdbc.query("""
                        SELECT * FROM container_review
                        WHERE container_id = ? AND custodian_id = ?
                        """,
                ROW_MAPPER, containerId, custodianId);
        return rows.stream().findFirst();
    }

    /**
     * 统计容器已有多少名不同保管人复核（调用前须锁定容器行）。
     */
    public int countByContainerId(String containerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM container_review WHERE container_id = ?",
                Integer.class, containerId);
        return count == null ? 0 : count;
    }

    /**
     * 按容器查询全部复核记录（按发生顺序）。
     */
    public List<ContainerReview> findByContainerId(String containerId) {
        return jdbc.query(
                "SELECT * FROM container_review WHERE container_id = ? ORDER BY id",
                ROW_MAPPER, containerId);
    }

    private static final class ReviewRowMapper implements RowMapper<ContainerReview> {
        @Override
        public ContainerReview mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerReview(
                    rs.getLong("id"),
                    rs.getString("container_id"),
                    rs.getString("custodian_id"),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
