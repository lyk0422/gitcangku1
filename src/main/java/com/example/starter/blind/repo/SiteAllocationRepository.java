package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 中心区组分配记录数据访问；分配记录只增不改不删。
 */
@Repository
public class SiteAllocationRepository {

    /** 中心区组分配行；不含处理代码与实验席位号。 */
    public record SiteAllocationRow(
            long id,
            String experimentId,
            String siteId,
            String blockCode,
            String participantId,
            int serialNo,
            String allocatorActor,
            long allocatedAt) {
    }

    private static final RowMapper<SiteAllocationRow> MAPPER = (rs, n) -> new SiteAllocationRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("site_id"),
            rs.getString("block_code"),
            rs.getString("participant_id"),
            rs.getInt("serial_no"),
            rs.getString("allocator_actor"),
            rs.getLong("allocated_at"));

    private static final String COLUMNS =
            "id, experiment_id, site_id, block_code, participant_id, serial_no, "
                    + "allocator_actor, allocated_at";

    private final JdbcTemplate jdbc;

    public SiteAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SiteAllocationRow row) {
        jdbc.update("INSERT INTO site_allocation ("
                        + "experiment_id, site_id, block_code, participant_id, serial_no, "
                        + "allocator_actor, allocated_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.siteId(), row.blockCode(), row.participantId(),
                row.serialNo(), row.allocatorActor(), row.allocatedAt());
    }

    public boolean isDuplicateParticipant(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_site_allocation_participant");
    }

    public boolean isDuplicateSerial(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_site_allocation_serial");
    }

    public SiteAllocationRow find(String experimentId, String siteId, String participantId) {
        List<SiteAllocationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM site_allocation "
                        + "WHERE experiment_id = ? AND site_id = ? AND participant_id = ?",
                MAPPER, experimentId, siteId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int countByBlock(String experimentId, String siteId, String blockCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation "
                        + "WHERE experiment_id = ? AND site_id = ? AND block_code = ?",
                Integer.class, experimentId, siteId, blockCode);
        return count == null ? 0 : count;
    }

    /**
     * 下一个区内顺序号；调用方须持有区组行锁（中心行锁串行化），保证不重号。
     */
    public int nextSerialNo(String experimentId, String siteId, String blockCode) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(serial_no) FROM site_allocation "
                        + "WHERE experiment_id = ? AND site_id = ? AND block_code = ?",
                Integer.class, experimentId, siteId, blockCode);
        return max == null ? 1 : max + 1;
    }
}
