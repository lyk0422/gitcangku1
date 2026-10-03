package com.example.starter.prediction;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 过境预测与预测序列的数据访问。
 */
@Repository
public class PredictionRepository {

    private static final RowMapper<PassPrediction> MAPPER = (rs, rowNum) -> new PassPrediction(
            rs.getLong("id"),
            rs.getString("prediction_key"),
            rs.getInt("version"),
            rs.getString("satellite_id"),
            rs.getTimestamp("window_start_utc").toInstant(),
            rs.getTimestamp("window_end_utc").toInstant(),
            rs.getBigDecimal("min_elevation_deg"),
            rs.getLong("planned_data_mb"),
            rs.getString("status"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public PredictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新建预测序列并加行锁（若已存在），返回当前版本号；不存在时返回空。
     */
    public Optional<Integer> lockSeriesCurrentVersion(String predictionKey) {
        return jdbc.query("SELECT current_version FROM prediction_series WHERE prediction_key = ? FOR UPDATE",
                (rs, rowNum) -> rs.getInt("current_version"), predictionKey).stream().findFirst();
    }

    /**
     * 创建预测序列，初始版本为 1。
     */
    public void insertSeries(String predictionKey) {
        jdbc.update("INSERT INTO prediction_series (prediction_key, current_version) VALUES (?, 1)",
                predictionKey);
    }

    /**
     * 推进序列当前版本。
     */
    public void advanceSeries(String predictionKey, int newVersion) {
        jdbc.update("UPDATE prediction_series SET current_version = ? WHERE prediction_key = ?",
                newVersion, predictionKey);
    }

    /**
     * 插入预测版本行并返回主键。
     */
    public long insert(PassPrediction prediction) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO pass_prediction (prediction_key, version, satellite_id, window_start_utc,"
                            + " window_end_utc, min_elevation_deg, planned_data_mb, status, created_at_utc)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, prediction.predictionKey());
            ps.setInt(2, prediction.version());
            ps.setString(3, prediction.satelliteId());
            ps.setTimestamp(4, Timestamp.from(prediction.windowStartUtc()));
            ps.setTimestamp(5, Timestamp.from(prediction.windowEndUtc()));
            ps.setBigDecimal(6, prediction.minElevationDeg());
            ps.setLong(7, prediction.plannedDataMb());
            ps.setString(8, prediction.status());
            ps.setTimestamp(9, Timestamp.from(prediction.createdAtUtc()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 将某业务键的生效版本标记为已取代。
     */
    public void supersedeCurrent(String predictionKey) {
        jdbc.update("UPDATE pass_prediction SET status = ? WHERE prediction_key = ? AND status = ?",
                PassPrediction.STATUS_SUPERSEDED, predictionKey, PassPrediction.STATUS_CURRENT);
    }

    /**
     * 查询某业务键当前生效版本。
     */
    public Optional<PassPrediction> findCurrent(String predictionKey) {
        return jdbc.query("SELECT * FROM pass_prediction WHERE prediction_key = ? AND status = ?",
                MAPPER, predictionKey, PassPrediction.STATUS_CURRENT).stream().findFirst();
    }

    /**
     * 按主键查询。
     */
    public Optional<PassPrediction> findById(long id) {
        return jdbc.query("SELECT * FROM pass_prediction WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 列出某业务键的全部版本，按版本号升序。
     */
    public List<PassPrediction> listVersions(String predictionKey) {
        return jdbc.query("SELECT * FROM pass_prediction WHERE prediction_key = ? ORDER BY version",
                MAPPER, predictionKey);
    }

    /**
     * 序列是否存在。
     */
    public boolean seriesExists(String predictionKey) {
        return !jdbc.queryForList("SELECT prediction_key FROM prediction_series WHERE prediction_key = ?",
                String.class, predictionKey).isEmpty();
    }
}