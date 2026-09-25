package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Optional;

/**
 * 发布单数据访问。扩量、取消、区域上限修改、拉取、回执共用行锁（SELECT ... FOR UPDATE）形成一致提交顺序。
 */
@Repository
public class ReleaseRepository {

    private static final RowMapper<ReleaseOrder> MAPPER = (rs, rowNum) -> new ReleaseOrder(
            rs.getLong("id"), rs.getInt("version"), rs.getString("model"),
            rs.getString("from_version"), rs.getString("to_version"),
            rs.getInt("ratio"), (Integer) rs.getObject("region_limit"),
            ReleaseStatus.valueOf(rs.getString("status")));

    private static final String COLUMNS = "id, version, model, from_version, to_version, ratio, region_limit, status";

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String fromVersion, String toVersion, int ratio, Integer regionLimit) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_order (version, model, from_version, to_version, ratio, region_limit,"
                            + " status, active_model) VALUES (1, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, fromVersion);
            ps.setString(3, toVersion);
            ps.setInt(4, ratio);
            if (regionLimit == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, regionLimit);
            }
            ps.setString(6, model);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReleaseOrder> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_order WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ReleaseOrder> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_order WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ReleaseOrder> findActiveByModel(String model) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_order WHERE active_model = ?", MAPPER, model)
                .stream().findFirst();
    }

    /**
     * 乐观扩量：仅当版本与状态匹配时生效，返回影响行数。
     */
    public int expand(long id, int expectedVersion, int newRatio) {
        return jdbc.update("UPDATE release_order SET version = version + 1, ratio = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND version = ? AND status = 'ACTIVE'", newRatio, id, expectedVersion);
    }

    /**
     * 乐观修改区域上限：仅当版本与状态匹配时生效，regionLimit 为 null 表示清除限流，返回影响行数。
     */
    public int updateRegionLimit(long id, int expectedVersion, Integer regionLimit) {
        return jdbc.update("UPDATE release_order SET version = version + 1, region_limit = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                regionLimit, id, expectedVersion);
    }

    public void cancel(long id) {
        jdbc.update("UPDATE release_order SET status = 'CANCELLED', active_model = NULL,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }
}
