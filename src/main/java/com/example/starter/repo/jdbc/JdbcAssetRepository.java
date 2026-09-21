package com.example.starter.repo.jdbc;

import com.example.starter.domain.Asset;
import com.example.starter.repo.AssetRepository;
import com.example.starter.repo.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 素材的 JDBC 持久化实现。
 */
@Repository
public class JdbcAssetRepository implements AssetRepository {

    private final JdbcTemplate jdbc;

    public JdbcAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Asset asset) {
        try {
            jdbc.update("INSERT INTO assets(id, duration_ms, created_at) VALUES (?,?,?)",
                    asset.id(), asset.durationMs(), System.currentTimeMillis());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DuplicateKeyException("素材已存在: " + asset.id());
        }
    }

    @Override
    public Optional<Asset> findById(String id) {
        List<Asset> rows = jdbc.query("SELECT id, duration_ms FROM assets WHERE id=?",
                (rs, i) -> new Asset(rs.getString("id"), rs.getLong("duration_ms")), id);
        return rows.stream().findFirst();
    }
}
