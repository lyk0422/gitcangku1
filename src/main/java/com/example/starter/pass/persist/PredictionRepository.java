package com.example.starter.pass.persist;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 过境预测版本数据访问。
 */
@Repository
public class PredictionRepository {

    private static final RowMapper<PassPredictionPo> MAPPER = (rs, n) -> {
        PassPredictionPo po = new PassPredictionPo();
        po.setId(rs.getLong("id"));
        po.setSatelliteCode(rs.getString("satellite_code"));
        po.setRevisionNo(rs.getInt("revision_no"));
        po.setVisibleStart(rs.getTimestamp("visible_start").toInstant());
        po.setVisibleEnd(rs.getTimestamp("visible_end").toInstant());
        po.setMinElevationDeg(rs.getBigDecimal("min_elevation_deg"));
        po.setPlannedBytes(rs.getLong("planned_bytes"));
        po.setSuperseded(rs.getBoolean("superseded"));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return po;
    };

    private final JdbcTemplate jdbcTemplate;

    public PredictionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入一个新版本，返回生成主键。 */
    public long insert(PassPredictionPo po) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO pass_prediction(satellite_code, revision_no, visible_start, visible_end, "
                            + "min_elevation_deg, planned_bytes, superseded, created_at) "
                            + "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, po.getSatelliteCode());
            ps.setInt(2, po.getRevisionNo());
            ps.setTimestamp(3, Timestamp.from(po.getVisibleStart()));
            ps.setTimestamp(4, Timestamp.from(po.getVisibleEnd()));
            ps.setBigDecimal(5, po.getMinElevationDeg());
            ps.setLong(6, po.getPlannedBytes());
            ps.setBoolean(7, po.isSuperseded());
            ps.setTimestamp(8, Timestamp.from(po.getCreatedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查版本。 */
    public Optional<PassPredictionPo> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM pass_prediction WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 查卫星的最新版本（含已失效版本，用于版本历史排序兜底）。 */
    public Optional<PassPredictionPo> findLatest(String satelliteCode) {
        return jdbcTemplate.query(
                        "SELECT * FROM pass_prediction WHERE satellite_code = ? "
                                + "ORDER BY revision_no DESC LIMIT 1", MAPPER, satelliteCode)
                .stream().findFirst();
    }

    /** 查卫星当前有效（未被取代）版本。 */
    public Optional<PassPredictionPo> findActive(String satelliteCode) {
        return jdbcTemplate.query(
                        "SELECT * FROM pass_prediction WHERE satellite_code = ? AND superseded = FALSE "
                                + "ORDER BY revision_no DESC LIMIT 1", MAPPER, satelliteCode)
                .stream().findFirst();
    }

    /** 将指定版本标记为已被取代。 */
    public int markSuperseded(long id) {
        return jdbcTemplate.update(
                "UPDATE pass_prediction SET superseded = TRUE WHERE id = ? AND superseded = FALSE", id);
    }

    /** 版本历史，按修订号倒序。 */
    public List<PassPredictionPo> findVersions(String satelliteCode) {
        return jdbcTemplate.query(
                "SELECT * FROM pass_prediction WHERE satellite_code = ? ORDER BY revision_no DESC",
                MAPPER, satelliteCode);
    }
}
