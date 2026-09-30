package com.example.starter.repository;

import com.example.starter.domain.GerminationTest;
import com.example.starter.domain.TestStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 发芽率检测记录表 germination_test 数据访问。
 */
@Repository
public class GerminationTestRepository {

    private final JdbcTemplate jdbcTemplate;

    public GerminationTestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String COLUMNS =
            "id, test_no, batch_id, sample_size, germinated_count, status, tested_at, voided_at, created_at";

    private static final RowMapper<GerminationTest> ROW_MAPPER = (rs, rowNum) -> new GerminationTest(
            rs.getLong("id"),
            rs.getString("test_no"),
            rs.getLong("batch_id"),
            rs.getInt("sample_size"),
            rs.getInt("germinated_count"),
            TestStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("tested_at").toLocalDateTime(),
            rs.getTimestamp("voided_at") == null ? null : rs.getTimestamp("voided_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime());

    /**
     * 追加一条新检测记录（复检同样走追加，不更新历史行），返回生成的主键。
     * 检测编号全库唯一，重复（含已归属其他批次）由唯一约束抛出 {@link DuplicateKeyException}。
     */
    public long insert(String testNo, long batchId, int sampleSize, int germinatedCount,
                       LocalDateTime testedAt, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO germination_test "
                            + "(test_no, batch_id, sample_size, germinated_count, status, tested_at, "
                            + "voided_at, created_at) "
                            + "VALUES (?, ?, ?, ?, 'VALID', ?, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, testNo);
            ps.setLong(2, batchId);
            ps.setInt(3, sampleSize);
            ps.setInt(4, germinatedCount);
            ps.setTimestamp(5, Timestamp.valueOf(testedAt));
            ps.setTimestamp(6, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按检测编号查询（检测编号全库唯一，因此可据此判断跨批次引用检查需要的信息）。
     */
    public Optional<GerminationTest> findByTestNo(String testNo) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM germination_test WHERE test_no = ?",
                ROW_MAPPER, testNo).stream().findFirst();
    }

    /**
     * 查询批次全部检测记录（含已作废），按检测时刻升序、同刻按主键升序形成检测链。
     */
    public List<GerminationTest> findChainByBatchId(long batchId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM germination_test WHERE batch_id = ? "
                        + "ORDER BY tested_at ASC, id ASC",
                ROW_MAPPER, batchId);
    }

    /**
     * 查询批次最近的若干条有效检测记录，按检测时刻倒序、同刻按主键倒序（[0] 为最近一次）。
     */
    public List<GerminationTest> findRecentValid(long batchId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM germination_test WHERE batch_id = ? AND status = 'VALID' "
                        + "ORDER BY tested_at DESC, id DESC LIMIT ?",
                ROW_MAPPER, batchId, limit);
    }

    /**
     * 将记录置为 VOID 并写入作废时刻；样本数、发芽粒数等历史内容不变。
     * 返回受影响行数：0 表示记录不存在或已是 VOID。
     */
    public int markVoid(long id, LocalDateTime voidedAt) {
        return jdbcTemplate.update(
                "UPDATE germination_test SET status = 'VOID', voided_at = ? WHERE id = ? AND status = 'VALID'",
                voidedAt == null ? null : Timestamp.valueOf(voidedAt), id);
    }
}
