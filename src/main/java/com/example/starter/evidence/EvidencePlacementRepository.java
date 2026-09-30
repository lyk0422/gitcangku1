package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 证物位置轨迹表访问。每次放入容器追加一条；移出时仅回填 removed_at，
 * 历史轨迹不随复核或后续移动改写。
 */
@Repository
public class EvidencePlacementRepository {

    private static final PlacementRowMapper ROW_MAPPER = new PlacementRowMapper();

    private final JdbcTemplate jdbc;

    public EvidencePlacementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条放入轨迹，位置取放入时容器位置快照。
     */
    public void insert(String evidenceKey, String containerKey, String location, LocalDateTime placedAt) {
        jdbc.update("""
                        INSERT INTO evidence_placement
                            (evidence_key, container_key, location, placed_at, removed_at)
                        VALUES (?, ?, ?, ?, NULL)
                        """,
                evidenceKey, containerKey, location, placedAt);
    }

    /**
     * 移出时回填当前在箱轨迹的移出时刻（调用前必须已锁定容器行与证物行）。
     */
    public void closeActive(String evidenceKey, LocalDateTime removedAt) {
        jdbc.update("""
                        UPDATE evidence_placement SET removed_at = ?
                        WHERE evidence_key = ? AND removed_at IS NULL
                        """,
                removedAt, evidenceKey);
    }

    /**
     * 按证物查询全部位置轨迹（按发生顺序），复核放行不改写。
     */
    public List<EvidencePlacement> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM evidence_placement WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class PlacementRowMapper implements RowMapper<EvidencePlacement> {
        @Override
        public EvidencePlacement mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EvidencePlacement(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("container_key"),
                    rs.getString("location"),
                    rs.getObject("placed_at", LocalDateTime.class),
                    rs.getObject("removed_at", LocalDateTime.class));
        }
    }
}
