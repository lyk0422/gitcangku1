package com.example.starter.repo;

import com.example.starter.domain.TagOwnership;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 标签登记与持有站点存取；findForUpdate 加行锁，用于串行化同一标签的并发操作。
 */
@Repository
public class TagOwnershipRepository {

    private final JdbcTemplate jdbc;

    public TagOwnershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按标签编号查询并锁定所有权行（SELECT ... FOR UPDATE）。
     */
    public Optional<TagOwnership> findForUpdate(String tagCode) {
        return jdbc.query(
                "SELECT tag_code, station_code, registered_at FROM tag_ownership WHERE tag_code = ? FOR UPDATE",
                (rs, rowNum) -> new TagOwnership(rs.getString("tag_code"), rs.getString("station_code"),
                        Times.toInstant(rs.getTimestamp("registered_at"))),
                tagCode).stream().findFirst();
    }

    /**
     * 登记新标签，初始持有站点为登记站点。
     */
    public void insert(String tagCode, String stationCode, Instant registeredAt) {
        jdbc.update("INSERT INTO tag_ownership (tag_code, station_code, registered_at) VALUES (?, ?, ?)",
                tagCode, stationCode, Times.toTimestamp(registeredAt));
    }

    /**
     * 更新当前持有站点（交接确认时调用）。
     */
    public void updateStation(String tagCode, String stationCode) {
        jdbc.update("UPDATE tag_ownership SET station_code = ? WHERE tag_code = ?", stationCode, tagCode);
    }
}