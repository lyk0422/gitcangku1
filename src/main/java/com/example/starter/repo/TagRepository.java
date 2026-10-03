package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.domain.TagRow;
import com.example.starter.domain.TagStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 标签表访问；持有站点与状态的并发修改先通过 FOR UPDATE 行锁串行化。
 */
@Repository
public class TagRepository {

    private final JdbcClient jdbc;

    public TagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TagRow> findByCode(String tagCode) {
        return jdbc.sql("SELECT tag_code, holder_station_code, status, registered_at FROM tag WHERE tag_code = :c")
                .param("c", tagCode)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** 事务内行锁读取，保证同一标签的并发操作按提交顺序串行判定。 */
    public Optional<TagRow> findByCodeForUpdate(String tagCode) {
        return jdbc.sql("SELECT tag_code, holder_station_code, status, registered_at FROM tag"
                + " WHERE tag_code = :c FOR UPDATE")
                .param("c", tagCode)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public void insert(String tagCode, String holderStationCode, Instant registeredAt) {
        jdbc.sql("INSERT INTO tag (tag_code, holder_station_code, status, registered_at)"
                + " VALUES (:c, :h, 'DETACHED', :t)")
                .param("c", tagCode)
                .param("h", holderStationCode)
                .param("t", Timestamp.from(registeredAt))
                .update();
    }

    public void updateStatus(String tagCode, TagStatus status) {
        jdbc.sql("UPDATE tag SET status = :s WHERE tag_code = :c")
                .param("s", status.name())
                .param("c", tagCode)
                .update();
    }

    public void updateHolder(String tagCode, String holderStationCode) {
        jdbc.sql("UPDATE tag SET holder_station_code = :h WHERE tag_code = :c")
                .param("h", holderStationCode)
                .param("c", tagCode)
                .update();
    }

    public boolean stationExists(String stationCode) {
        return jdbc.sql("SELECT COUNT(*) FROM station WHERE station_code = :c")
                .param("c", stationCode)
                .query(Long.class)
                .single() > 0;
    }

    private TagRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TagRow(
                rs.getString("tag_code"),
                rs.getString("holder_station_code"),
                TagStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("registered_at").toInstant());
    }
}