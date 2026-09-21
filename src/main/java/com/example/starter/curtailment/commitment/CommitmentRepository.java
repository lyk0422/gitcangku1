package com.example.starter.curtailment.commitment;

import com.example.starter.curtailment.common.UtcTimes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 容量承诺持久化：全部使用参数化 SQL，时间列按 UTC 读写。
 */
@Repository
public class CommitmentRepository {

    private static final RowMapper<Commitment> MAPPER = (rs, rowNum) -> new Commitment(
            rs.getLong("id"),
            rs.getString("commitment_key"),
            rs.getString("site_id"),
            UtcTimes.toInstant(rs.getObject("valid_from_utc", LocalDateTime.class)),
            UtcTimes.toInstant(rs.getObject("valid_to_utc", LocalDateTime.class)),
            rs.getBigDecimal("max_power_kw"),
            CommitmentStatus.valueOf(rs.getString("status")),
            UtcTimes.toInstant(rs.getObject("created_at_utc", LocalDateTime.class)),
            UtcTimes.toInstant(rs.getObject("updated_at_utc", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CommitmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Commitment insert(Commitment commitment) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO capacity_commitment (commitment_key, site_id, valid_from_utc, valid_to_utc,"
                            + " max_power_kw, status, created_at_utc, updated_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, commitment.commitmentKey());
            ps.setString(2, commitment.siteId());
            ps.setObject(3, UtcTimes.toUtcDateTime(commitment.validFrom()));
            ps.setObject(4, UtcTimes.toUtcDateTime(commitment.validTo()));
            ps.setBigDecimal(5, commitment.maxPowerKw());
            ps.setString(6, commitment.status().name());
            ps.setObject(7, UtcTimes.toUtcDateTime(commitment.createdAt()));
            ps.setObject(8, UtcTimes.toUtcDateTime(commitment.updatedAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new Commitment(id, commitment.commitmentKey(), commitment.siteId(), commitment.validFrom(),
                commitment.validTo(), commitment.maxPowerKw(), commitment.status(), commitment.createdAt(),
                commitment.updatedAt());
    }

    public Optional<Commitment> findByKey(String commitmentKey) {
        return jdbc.query("SELECT * FROM capacity_commitment WHERE commitment_key = ?", MAPPER, commitmentKey)
                .stream()
                .findFirst();
    }

    /** 锁读承诺行：暂停与发布校验并发时按行锁串行。 */
    public Optional<Commitment> findByKeyForUpdate(String commitmentKey) {
        return jdbc.query("SELECT * FROM capacity_commitment WHERE commitment_key = ? FOR UPDATE", MAPPER,
                        commitmentKey)
                .stream()
                .findFirst();
    }

    /** 同一站点时间重叠（含端点相交、不含相邻）的承诺数量。 */
    public int countOverlapping(String siteId, Instant validFrom, Instant validTo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM capacity_commitment WHERE site_id = ? AND valid_from_utc < ? AND valid_to_utc > ?",
                Integer.class, siteId, UtcTimes.toUtcDateTime(validTo), UtcTimes.toUtcDateTime(validFrom));
        return count == null ? 0 : count;
    }

    /** 锁读完整覆盖指定区间的有效承诺；正常情形下至多一条。 */
    public List<Commitment> findActiveCoveringForUpdate(String siteId, Instant from, Instant to) {
        return jdbc.query(
                "SELECT * FROM capacity_commitment WHERE site_id = ? AND status = 'ACTIVE'"
                        + " AND valid_from_utc <= ? AND valid_to_utc >= ? ORDER BY id FOR UPDATE",
                MAPPER, siteId, UtcTimes.toUtcDateTime(from), UtcTimes.toUtcDateTime(to));
    }

    public void updateStatus(long id, CommitmentStatus status, Instant updatedAt) {
        jdbc.update("UPDATE capacity_commitment SET status = ?, updated_at_utc = ? WHERE id = ?",
                status.name(), UtcTimes.toUtcDateTime(updatedAt), id);
    }
}
