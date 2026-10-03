package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.AttachmentRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 附着记录表访问；区间判定一律左闭右开：[startAt, endAt)。
 */
@Repository
public class AttachmentRepository {

    private static final String COLUMNS = "id, tag_code, animal_code, station_code, start_at, end_at";

    private final JdbcClient jdbc;

    public AttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AttachmentRow insert(String tagCode, String animalCode, String stationCode, Instant startAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO attachment (tag_code, animal_code, station_code, start_at)"
                + " VALUES (:t, :a, :s, :st)")
                .param("t", tagCode)
                .param("a", animalCode)
                .param("s", stationCode)
                .param("st", Timestamp.from(startAt))
                .update(keys);
        Number key = keys.getKey();
        return new AttachmentRow(key == null ? null : key.longValue(), tagCode, animalCode, stationCode, startAt, null);
    }

    public Optional<AttachmentRow> findOpenByTag(String tagCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attachment WHERE tag_code = :t AND end_at IS NULL")
                .param("t", tagCode)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** 动物在 startAt 之后（含）是否已有有效区间：end_at 为 NULL 或晚于 startAt 即交叉。 */
    public boolean existsAnimalOverlap(String animalCode, Instant startAt) {
        return jdbc.sql("SELECT COUNT(*) FROM attachment"
                + " WHERE animal_code = :a AND (end_at IS NULL OR end_at > :st)")
                .param("a", animalCode)
                .param("st", Timestamp.from(startAt))
                .query(Long.class)
                .single() > 0;
    }

    /** 标签在 startAt 之后（含）是否已有区间，用于拒绝时间倒退的附着。 */
    public boolean existsTagOverlap(String tagCode, Instant startAt) {
        return jdbc.sql("SELECT COUNT(*) FROM attachment"
                + " WHERE tag_code = :t AND (end_at IS NULL OR end_at > :st)")
                .param("t", tagCode)
                .param("st", Timestamp.from(startAt))
                .query(Long.class)
                .single() > 0;
    }

    public void close(long id, Instant endAt) {
        jdbc.sql("UPDATE attachment SET end_at = :e WHERE id = :id")
                .param("e", Timestamp.from(endAt))
                .param("id", id)
                .update();
    }

    public List<AttachmentRow> findByTag(String tagCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attachment WHERE tag_code = :t ORDER BY start_at, id")
                .param("t", tagCode)
                .query((rs, n) -> map(rs))
                .list();
    }

    public List<AttachmentRow> findByAnimal(String animalCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attachment WHERE animal_code = :a ORDER BY start_at, id")
                .param("a", animalCode)
                .query((rs, n) -> map(rs))
                .list();
    }

    private AttachmentRow map(ResultSet rs) throws SQLException {
        Timestamp end = rs.getTimestamp("end_at");
        return new AttachmentRow(
                rs.getLong("id"),
                rs.getString("tag_code"),
                rs.getString("animal_code"),
                rs.getString("station_code"),
                rs.getTimestamp("start_at").toInstant(),
                end == null ? null : end.toInstant());
    }
}