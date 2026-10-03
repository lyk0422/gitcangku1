package com.example.starter.repo;

import com.example.starter.domain.Attachment;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 附着记录存取：摘除即冻结为历史，查询按左闭右开区间解释。
 */
@Repository
public class AttachmentRepository {

    private static final RowMapper<Attachment> MAPPER = (rs, rowNum) -> new Attachment(
            rs.getLong("id"), rs.getString("tag_code"), rs.getString("animal_code"),
            rs.getString("station_code"), Times.toInstant(rs.getTimestamp("attached_at")),
            Times.toInstant(rs.getTimestamp("detached_at")), rs.getString("request_id"));

    private final JdbcTemplate jdbc;

    public AttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询标签当前仍有效的附着（detached_at 为 NULL）。
     */
    public Optional<Attachment> findOpenByTag(String tagCode) {
        return jdbc.query("SELECT * FROM attachment WHERE tag_code = ? AND detached_at IS NULL",
                MAPPER, tagCode).stream().findFirst();
    }

    /**
     * 查询动物当前仍有效的附着（detached_at 为 NULL）。
     */
    public Optional<Attachment> findOpenByAnimal(String animalCode) {
        return jdbc.query("SELECT * FROM attachment WHERE animal_code = ? AND detached_at IS NULL",
                MAPPER, animalCode).stream().findFirst();
    }

    /**
     * 标签历史上最大的摘除时刻，用于判定新附着是否时间倒退或区间交叉。
     */
    public Optional<Instant> maxDetachedAtByTag(String tagCode) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MAX(detached_at) FROM attachment WHERE tag_code = ?", Timestamp.class, tagCode);
        return Optional.ofNullable(Times.toInstant(ts));
    }

    /**
     * 动物历史上最大的摘除时刻，用于判定新附着是否时间倒退或区间交叉。
     */
    public Optional<Instant> maxDetachedAtByAnimal(String animalCode) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MAX(detached_at) FROM attachment WHERE animal_code = ?", Timestamp.class, animalCode);
        return Optional.ofNullable(Times.toInstant(ts));
    }

    /**
     * 写入新附着记录并返回主键。
     */
    public long insert(String tagCode, String animalCode, String stationCode,
            Instant attachedAt, String requestId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO attachment (tag_code, animal_code, station_code, attached_at, request_id," + " active_tag_key, active_animal_key)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, tagCode);
            ps.setString(2, animalCode);
            ps.setString(3, stationCode);
            ps.setTimestamp(4, Times.toTimestamp(attachedAt));
            ps.setString(5, requestId);
            ps.setString(6, tagCode);
            ps.setString(7, animalCode);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 摘除：冻结指定附着记录的右端点。
     */
    public void close(long id, Instant detachedAt) {
        jdbc.update("UPDATE attachment SET detached_at = ?, active_tag_key = NULL, active_animal_key = NULL" + " WHERE id = ?",
                Times.toTimestamp(detachedAt), id);
    }

    /**
     * 按标签查询全部附着历史，按开始时刻升序。
     */
    public List<Attachment> listByTag(String tagCode) {
        return jdbc.query("SELECT * FROM attachment WHERE tag_code = ? ORDER BY attached_at, id",
                MAPPER, tagCode);
    }

    /**
     * 按动物查询全部附着历史，按开始时刻升序。
     */
    public List<Attachment> listByAnimal(String animalCode) {
        return jdbc.query("SELECT * FROM attachment WHERE animal_code = ? ORDER BY attached_at, id",
                MAPPER, animalCode);
    }
}