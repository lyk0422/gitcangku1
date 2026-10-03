package com.example.starter.repo;

import com.example.starter.model.PassPrediction;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 卫星过境预测数据访问。时间列以 UTC 墙钟时间存储。
 */
@Repository
public class PassPredictionRepository {

    private static final RowMapper<PassPrediction> MAPPER = (rs, n) -> new PassPrediction(
            rs.getLong("id"),
            rs.getString("satellite_code"),
            rs.getInt("version"),
            rs.getObject("window_start_utc", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("window_end_utc", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getBigDecimal("min_elevation_deg"),
            rs.getLong("planned_bytes"),
            rs.getBoolean("superseded"),
            rs.getObject("created_at_utc", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;

    public PassPredictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 插入新版本预测并返回生成主键。 */
    public long insert(PassPrediction p) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pass_prediction (satellite_code, version, window_start_utc,"
                            + " window_end_utc, min_elevation_deg, planned_bytes, superseded, created_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, p.satelliteCode());
            ps.setInt(2, p.version());
            ps.setTimestamp(3, Timestamp.valueOf(toUtc(p.windowStartUtc())));
            ps.setTimestamp(4, Timestamp.valueOf(toUtc(p.windowEndUtc())));
            ps.setBigDecimal(5, p.minElevationDeg());
            ps.setLong(6, p.plannedBytes());
            ps.setBoolean(7, p.superseded());
            ps.setTimestamp(8, Timestamp.valueOf(toUtc(p.createdAtUtc())));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 查询卫星当前最新版本预测。 */
    public Optional<PassPrediction> findLatest(String satelliteCode) {
        List<PassPrediction> rows = jdbc.query(
                "SELECT * FROM pass_prediction WHERE satellite_code = ?"
                        + " ORDER BY version DESC LIMIT 1",
                MAPPER, satelliteCode);
        return rows.stream().findFirst();
    }

    /**
     * 查询并锁定卫星最新版本预测行（FOR UPDATE），
     * 确认时隙时持有该锁，与预测修订串行化，避免凭过期预测锁定天线。
     */
    public Optional<PassPrediction> lockLatest(String satelliteCode) {
        List<PassPrediction> rows = jdbc.query(
                "SELECT * FROM pass_prediction WHERE satellite_code = ?"
                        + " ORDER BY version DESC LIMIT 1 FOR UPDATE",
                MAPPER, satelliteCode);
        return rows.stream().findFirst();
    }

    /** 按主键查询。 */
    public Optional<PassPrediction> findById(long id) {
        List<PassPrediction> rows = jdbc.query(
                "SELECT * FROM pass_prediction WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 查询卫星全部版本，按版本号升序。 */
    public List<PassPrediction> findVersions(String satelliteCode) {
        return jdbc.query(
                "SELECT * FROM pass_prediction WHERE satellite_code = ? ORDER BY version",
                MAPPER, satelliteCode);
    }

    /** 将指定预测标记为已被取代。 */
    public int markSuperseded(long id) {
        return jdbc.update("UPDATE pass_prediction SET superseded = TRUE WHERE id = ?", id);
    }
}