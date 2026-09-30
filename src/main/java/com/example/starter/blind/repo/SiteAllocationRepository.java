package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 中心分配数据访问；只保存盲码与区组号，不保存处理代码。
 */
@Repository
public class SiteAllocationRepository {

    /** 中心分配行。 */
    public record SiteAllocationRow(
            long id,
            String siteId,
            int version,
            String participantId,
            int blockNo,
            String blindCode,
            String assignedActor,
            long assignedAt) {
    }

    private static final RowMapper<SiteAllocationRow> MAPPER = (rs, n) -> new SiteAllocationRow(
            rs.getLong("id"),
            rs.getString("site_id"),
            rs.getInt("version"),
            rs.getString("participant_id"),
            rs.getInt("block_no"),
            rs.getString("blind_code"),
            rs.getString("assigned_actor"),
            rs.getLong("assigned_at"));

    private final JdbcTemplate jdbc;

    public SiteAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SiteAllocationRow row) {
        jdbc.update("INSERT INTO site_allocation ("
                        + "site_id, version, participant_id, block_no, blind_code, "
                        + "assigned_actor, assigned_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.siteId(), row.version(), row.participantId(), row.blockNo(), row.blindCode(),
                row.assignedActor(), row.assignedAt());
    }

    public SiteAllocationRow findBySiteAndParticipant(String siteId, String participantId) {
        List<SiteAllocationRow> rows = jdbc.query(
                "SELECT id, site_id, version, participant_id, block_no, blind_code, "
                        + "assigned_actor, assigned_at "
                        + "FROM site_allocation WHERE site_id = ? AND participant_id = ?",
                MAPPER, siteId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 指定冻结版本下某区组的已分配量。 */
    public int countByBlock(String siteId, int version, int blockNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE site_id = ? AND version = ? AND block_no = ?",
                Integer.class, siteId, version, blockNo);
        return count == null ? 0 : count;
    }

    /**
     * 插入时若参与者已在同中心占席，抛出由唯一约束触发的 DuplicateKeyException。
     */
    public boolean isDuplicateParticipant(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_site_allocation_participant");
    }

    public boolean isDuplicateBlindCode(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_site_allocation_blind_code");
    }
}
