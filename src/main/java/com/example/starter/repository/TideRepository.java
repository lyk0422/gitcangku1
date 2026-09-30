package com.example.starter.repository;

import com.example.starter.domain.TideVersion;
import com.example.starter.domain.TideWindow;
import com.example.starter.web.dto.PublishTideRequest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 潮高版本与潮高区间数据访问。
 */
@Repository
public class TideRepository {

    private static final TideVersionRowMapper VERSION_MAPPER = new TideVersionRowMapper();

    private static final TideWindowRowMapper WINDOW_MAPPER = new TideWindowRowMapper();

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 模板
     */
    public TideRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一个潮高版本。
     *
     * @param versionNo   版本号（唯一）
     * @param publishedAt 发布时间
     * @param remark      备注，可为空
     * @return 带主键的版本实体
     */
    public TideVersion insertVersion(int versionNo, LocalDateTime publishedAt, String remark) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO tide_version (version_no, published_at, remark) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setInt(1, versionNo);
            ps.setTimestamp(2, Timestamp.valueOf(publishedAt));
            ps.setString(3, remark);
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new TideVersion(id, versionNo, publishedAt, remark);
    }

    /**
     * 批量插入某版本的潮高区间。
     *
     * @param versionId 版本主键
     * @param windows   区间集合
     */
    public void insertWindows(long versionId, List<PublishTideRequest.WindowDto> windows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO tide_window (version_id, start_time, end_time, tide_height_m) VALUES (?, ?, ?, ?)",
                windows, windows.size(), (ps, window) -> {
                    ps.setLong(1, versionId);
                    ps.setTimestamp(2, Timestamp.valueOf(window.startTime()));
                    ps.setTimestamp(3, Timestamp.valueOf(window.endTime()));
                    ps.setBigDecimal(4, window.tideHeightM());
                });
    }

    /**
     * @return 当前最新（版本号最大）潮高版本
     */
    public Optional<TideVersion> findLatestVersion() {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id, version_no, published_at, remark FROM tide_version "
                            + "ORDER BY version_no DESC LIMIT 1", VERSION_MAPPER));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 查询某版本下与申请区间 [start, end) 相交的全部潮高区间，按起始时间升序。
     * 取相交部分（窗口可超出申请范围），潮高值不变。
     *
     * @param versionId 版本主键
     * @param start     申请起始时间
     * @param end       申请结束时间
     * @return 潮高区间集合
     */
    public List<TideWindow> findIntersectingWindows(long versionId, LocalDateTime start, LocalDateTime end) {
        return jdbcTemplate.query(
                "SELECT start_time, end_time, tide_height_m FROM tide_window "
                        + "WHERE version_id = ? AND start_time < ? AND end_time > ? "
                        + "ORDER BY start_time ASC",
                WINDOW_MAPPER, versionId, Timestamp.valueOf(end), Timestamp.valueOf(start));
    }

    /**
     * 潮高版本行映射器。
     */
    static class TideVersionRowMapper implements RowMapper<TideVersion> {

        @Override
        public TideVersion mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp publishedAt = rs.getTimestamp("published_at");
            return new TideVersion(
                    rs.getLong("id"),
                    rs.getInt("version_no"),
                    publishedAt == null ? null : publishedAt.toLocalDateTime(),
                    rs.getString("remark"));
        }
    }

    /**
     * 潮高区间行映射器。
     */
    static class TideWindowRowMapper implements RowMapper<TideWindow> {

        @Override
        public TideWindow mapRow(ResultSet rs, int rowNum) throws SQLException {
            BigDecimal tideHeight = rs.getBigDecimal("tide_height_m");
            return new TideWindow(
                    rs.getTimestamp("start_time").toLocalDateTime(),
                    rs.getTimestamp("end_time").toLocalDateTime(),
                    tideHeight);
        }
    }
}
