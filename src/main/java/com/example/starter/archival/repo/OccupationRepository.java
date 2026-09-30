package com.example.starter.archival.repo;

import com.example.starter.archival.domain.PageOccupation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 页面处理占用仓储。page_id 主键是“同一页同一时刻只能被一个进行中批次占用”的数据库唯一约束。
 */
@Repository
public class OccupationRepository {

    private static final RowMapper<PageOccupation> MAPPER = (rs, rowNum) -> new PageOccupation(
            rs.getLong("page_id"),
            rs.getLong("batch_id"),
            rs.getTimestamp("held_since").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public OccupationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占用页面。被其他进行中批次占用时触发主键冲突（{@link DuplicateKeyException}），
     * 由服务层转换为跨批次占用冲突。
     */
    public int tryAcquire(long pageId, long batchId, LocalDateTime heldSince) {
        return jdbc.update(
                "INSERT INTO page_occupation (page_id, batch_id, held_since) VALUES (?, ?, ?)",
                pageId, batchId, Timestamp.valueOf(heldSince));
    }

    /** 释放占用；仅当占用者确为指定批次时生效（返回受影响行数）。 */
    public int release(long pageId, long batchId) {
        return jdbc.update(
                "DELETE FROM page_occupation WHERE page_id = ? AND batch_id = ?", pageId, batchId);
    }

    /** 查询页面当前占用。 */
    public Optional<PageOccupation> findByPage(long pageId) {
        List<PageOccupation> list = jdbc.query(
                "SELECT page_id, batch_id, held_since FROM page_occupation WHERE page_id = ?",
                MAPPER, pageId);
        return list.stream().findFirst();
    }

    /** 释放批次当前持有的全部占用（封存时批量释放）。 */
    public int releaseByBatch(long batchId) {
        return jdbc.update("DELETE FROM page_occupation WHERE batch_id = ?", batchId);
    }
}
