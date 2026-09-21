package com.example.starter.repo.jdbc;

import com.example.starter.domain.Grant;
import com.example.starter.repo.GrantRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 授权的 JDBC 持久化实现。
 */
@Repository
public class JdbcGrantRepository implements GrantRepository {

    private final JdbcTemplate jdbc;

    public JdbcGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Grant grant) {
        jdbc.update("INSERT INTO grants(id, channel_id, asset_id, valid_from, valid_to, revoked, created_at)"
                        + " VALUES (?,?,?,?,?,?,?)",
                grant.id(), grant.channelId(), grant.assetId(),
                grant.validFrom().toEpochMilli(), grant.validTo().toEpochMilli(),
                grant.revoked(), System.currentTimeMillis());
    }

    @Override
    public Optional<Grant> findById(String id) {
        List<Grant> rows = jdbc.query(
                "SELECT id, channel_id, asset_id, valid_from, valid_to, revoked FROM grants WHERE id=?",
                (rs, i) -> new Grant(rs.getString("id"), rs.getString("channel_id"),
                        rs.getString("asset_id"), Instant.ofEpochMilli(rs.getLong("valid_from")),
                        Instant.ofEpochMilli(rs.getLong("valid_to")), rs.getBoolean("revoked")),
                id);
        return rows.stream().findFirst();
    }

    @Override
    public boolean existsCovering(String channelId, String assetId, Instant start, Instant end) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM grants WHERE channel_id=? AND asset_id=? AND revoked=FALSE"
                        + " AND valid_from<=? AND valid_to>=?",
                Integer.class, channelId, assetId, start.toEpochMilli(), end.toEpochMilli());
        return count != null && count > 0;
    }

    @Override
    public Optional<Grant> markRevoked(String id) {
        jdbc.update("UPDATE grants SET revoked=TRUE WHERE id=?", id);
        return findById(id);
    }
}
