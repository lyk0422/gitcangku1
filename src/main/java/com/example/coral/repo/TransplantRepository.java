package com.example.coral.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.coral.domain.PlantStatus;
import com.example.coral.domain.Transplant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 移植登记持久化：植株编号与请求编号均有唯一约束，死亡记录保留不删。
 */
@Repository
public class TransplantRepository {

    private static final RowMapper<Transplant> MAPPER = (rs, n) -> new Transplant(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("plant_code"),
            rs.getString("nursery"),
            rs.getLong("quadrat_id"),
            PlantStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("transplanted_at").toInstant());

    private final JdbcTemplate jdbc;

    public TransplantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String requestId, String plantCode, String nursery, long quadratId,
            Instant transplantedAt) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO transplant (request_id, plant_code, nursery, quadrat_id, status,"
                            + " transplanted_at) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, plantCode);
            ps.setString(3, nursery);
            ps.setLong(4, quadratId);
            ps.setTimestamp(5, Timestamp.from(transplantedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Transplant> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM transplant WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<Transplant> findByPlantCode(String plantCode) {
        return jdbc.query("SELECT * FROM transplant WHERE plant_code = ?", MAPPER, plantCode)
                .stream().findFirst();
    }

    /** 活跃移植体数量（未死亡），容量上限12的判定依据。 */
    public int countActiveByQuadratId(long quadratId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transplant WHERE quadrat_id = ? AND status <> 'DEAD'",
                Integer.class, quadratId);
    }

    public int countByQuadratIdAndStatus(long quadratId, PlantStatus status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transplant WHERE quadrat_id = ? AND status = ?",
                Integer.class, quadratId, status.name());
    }

    /** 样方内全部移植记录（含死亡），按登记顺序。 */
    public List<Transplant> findByQuadratId(long quadratId) {
        return jdbc.query("SELECT * FROM transplant WHERE quadrat_id = ? ORDER BY id", MAPPER, quadratId);
    }

    public void updateStatus(long id, PlantStatus status) {
        jdbc.update("UPDATE transplant SET status = ? WHERE id = ?", status.name(), id);
    }
}