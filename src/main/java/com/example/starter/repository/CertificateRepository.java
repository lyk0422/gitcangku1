package com.example.starter.repository;

import com.example.starter.domain.CertificateStatus;
import com.example.starter.domain.GerminationCertificate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * 发芽率证书表 germination_certificate 数据访问。证书行内容不可变，失效仅更新 status。
 */
@Repository
public class CertificateRepository {

    private final JdbcTemplate jdbcTemplate;

    public CertificateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String COLUMNS =
            "id, certificate_no, batch_id, first_test_no, first_sample_size, first_germinated_count, "
                    + "first_ratio, second_test_no, second_sample_size, second_germinated_count, "
                    + "second_ratio, status, request_id, issued_at, created_at";

    private static final RowMapper<GerminationCertificate> ROW_MAPPER = (rs, rowNum) ->
            new GerminationCertificate(
                    rs.getLong("id"),
                    rs.getString("certificate_no"),
                    rs.getLong("batch_id"),
                    rs.getString("first_test_no"),
                    rs.getInt("first_sample_size"),
                    rs.getInt("first_germinated_count"),
                    rs.getBigDecimal("first_ratio"),
                    rs.getString("second_test_no"),
                    rs.getInt("second_sample_size"),
                    rs.getInt("second_germinated_count"),
                    rs.getBigDecimal("second_ratio"),
                    CertificateStatus.valueOf(rs.getString("status")),
                    rs.getString("request_id"),
                    rs.getTimestamp("issued_at").toLocalDateTime(),
                    rs.getTimestamp("created_at").toLocalDateTime());

    /**
     * 插入证书并固化两次检测的编号、样本数、发芽粒数、比例、签发时刻与签发 requestId，返回主键。
     */
    public long insert(GerminationCertificate c) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO germination_certificate ("
                            + "certificate_no, batch_id, first_test_no, first_sample_size, "
                            + "first_germinated_count, first_ratio, second_test_no, second_sample_size, "
                            + "second_germinated_count, second_ratio, status, request_id, issued_at, created_at"
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.certificateNo());
            ps.setLong(2, c.batchId());
            ps.setString(3, c.firstTestNo());
            ps.setInt(4, c.firstSampleSize());
            ps.setInt(5, c.firstGerminatedCount());
            ps.setBigDecimal(6, c.firstRatio());
            ps.setString(7, c.secondTestNo());
            ps.setInt(8, c.secondSampleSize());
            ps.setInt(9, c.secondGerminatedCount());
            ps.setBigDecimal(10, c.secondRatio());
            ps.setString(11, c.status().name());
            ps.setString(12, c.requestId());
            ps.setTimestamp(13, Timestamp.valueOf(c.issuedAt()));
            ps.setTimestamp(14, Timestamp.valueOf(c.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询批次最近签发的一张证书（可能为 VALID 或 INVALID）；从未签发返回空。
     */
    public Optional<GerminationCertificate> findLatestByBatchId(long batchId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM germination_certificate WHERE batch_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, batchId).stream().findFirst();
    }

    /**
     * 统计批次证书数量，用于在批次行锁内生成确定性证书编号。
     */
    public int countByBatchId(long batchId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM germination_certificate WHERE batch_id = ?",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /**
     * 是否存在指定状态、且固化引用了给定检测编号的证书。
     * 检测记录一旦被有效证书固化即视为已使用，再次签发时必须拒绝，不产生部分证书。
     */
    public boolean existsByStatusAndTestNo(CertificateStatus status, String testNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM germination_certificate WHERE status = ? "
                        + "AND (first_test_no = ? OR second_test_no = ?)",
                Integer.class, status.name(), testNo, testNo);
        return count != null && count > 0;
    }

    /**
     * 将证书置为 INVALID；返回受影响行数。
     */
    public int markInvalid(long id) {
        return jdbcTemplate.update(
                "UPDATE germination_certificate SET status = 'INVALID' WHERE id = ? AND status = 'VALID'",
                id);
    }
}
