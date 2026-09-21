package com.example.starter.repo.jdbc;

import com.example.starter.domain.Channel;
import com.example.starter.repo.ChannelRepository;
import com.example.starter.repo.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 频道的 JDBC 持久化实现。
 */
@Repository
public class JdbcChannelRepository implements ChannelRepository {

    private final JdbcTemplate jdbc;

    public JdbcChannelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Channel channel) {
        try {
            jdbc.update("INSERT INTO channels(id, fallback_asset_id, created_at) VALUES (?,?,?)",
                    channel.id(), channel.fallbackAssetId(), System.currentTimeMillis());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DuplicateKeyException("频道已存在: " + channel.id());
        }
    }

    @Override
    public Optional<Channel> findById(String id) {
        List<Channel> rows = jdbc.query(
                "SELECT id, fallback_asset_id FROM channels WHERE id=?",
                (rs, i) -> new Channel(rs.getString("id"), rs.getString("fallback_asset_id")), id);
        return rows.stream().findFirst();
    }
}
