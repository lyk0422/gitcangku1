package com.example.starter.germination.repository;

import com.example.starter.germination.domain.BatchRelease;
import com.example.starter.germination.domain.CertificateStatus;
import com.example.starter.germination.domain.GerminationCertificate;
import com.example.starter.germination.domain.GerminationTest;
import com.example.starter.germination.domain.SeedBatch;
import com.example.starter.germination.domain.TestStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 发芽率业务数据访问；所有 FOR UPDATE 方法必须在事务内调用，用于按批次串行化写入。
 */
@Repository
public class GerminationRepository {

    private final JdbcTemplate jdbc;

    public GerminationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SeedBatch> BATCH_MAPPER = (rs, n) ->
            new SeedBatch(rs.getString("batch_no"),
                    rs.getString("crop_name"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<GerminationTest> TEST_MAPPER = (rs, n) -> {
        java.sql.Timestamp voidedAt = rs.getTimestamp("voided_at");
        return new GerminationTest(rs.getString("test_no"),
                rs.getString("batch_no"),
                rs.getInt("sample_count"),
                rs.getInt("germinated_count"),
                TestStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("recorded_at").toInstant(),
                voidedAt == null ? null : voidedAt.toInstant());
    };

    private static final RowMapper<GerminationCertificate> CERT_MAPPER = (rs, n) ->
            new GerminationCertificate(rs.getString("cert_no"),
                    rs.getString("batch_no"),
                    rs.getString("first_test_no"),
                    rs.getBigDecimal("first_ratio"),
                    rs.getString("second_test_no"),
                    rs.getBigDecimal("second_ratio"),
                    CertificateStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("issued_at").toInstant());

    private static final RowMapper<BatchRelease> RELEASE_MAPPER = (rs, n) ->
            new BatchRelease(rs.getString("batch_no"),
                    rs.getString("cert_no"),
                    rs.getTimestamp("released_at").toInstant());

    /** 按编号查批次。 */
    public Optional<SeedBatch> findBatch(String batchNo) {
        return jdbc.query("SELECT batch_no, crop_name, created_at FROM seed_batch WHERE batch_no = ?",
                BATCH_MAPPER, batchNo).stream().findFirst();
    }

    /** 锁定批次行（SELECT ... FOR UPDATE），不存在返回空；用于作废/签发/放行的互斥。 */
    public Optional<String> lockBatch(String batchNo) {
        return jdbc.query("SELECT batch_no FROM seed_batch WHERE batch_no = ? FOR UPDATE",
                (rs, n) -> rs.getString("batch_no"), batchNo).stream().findFirst();
    }

    /** 插入检测记录；检测编号主键冲突时抛 Spring 重复键异常，由上层处理。 */
    public void insertTest(GerminationTest test) {
        jdbc.update("INSERT INTO germination_test "
                        + "(test_no, batch_no, sample_count, germinated_count, status, recorded_at, voided_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                test.testNo(), test.batchNo(), test.sampleCount(), test.germinatedCount(),
                test.status().name(), Timestamp.from(test.recordedAt()),
                test.voidedAt() == null ? null : Timestamp.from(test.voidedAt()));
    }

    /** 按检测编号查记录。 */
    public Optional<GerminationTest> findTest(String testNo) {
        return jdbc.query(TEST_SQL + " WHERE t.test_no = ?",
                TEST_MAPPER, testNo).stream().findFirst();
    }

    /** 事务内按检测编号锁定记录行。 */
    public Optional<GerminationTest> findTestForUpdate(String testNo) {
        return jdbc.query(TEST_SQL + " WHERE t.test_no = ? FOR UPDATE",
                TEST_MAPPER, testNo).stream().findFirst();
    }

    /** 按批次列出全部检测（含作废），按登记时刻、检测编号排序。 */
    public List<GerminationTest> findTestsByBatch(String batchNo) {
        return jdbc.query(TEST_SQL + " WHERE t.batch_no = ? ORDER BY t.recorded_at, t.test_no",
                TEST_MAPPER, batchNo);
    }

    private static final String TEST_SQL =
            "SELECT t.test_no, t.batch_no, t.sample_count, t.germinated_count, "
                    + "t.status, t.recorded_at, t.voided_at FROM germination_test t";

    /** 将检测记录置为作废（历史内容不改写，仅状态与作废时刻变化），返回受影响行数。 */
    public int markTestVoid(String testNo, Instant voidedAt) {
        return jdbc.update("UPDATE germination_test SET status = 'VOID', voided_at = ? "
                + "WHERE test_no = ? AND status = 'VALID'", Timestamp.from(voidedAt), testNo);
    }

    /** 插入证书。 */
    public void insertCertificate(GerminationCertificate certificate) {
        jdbc.update("INSERT INTO germination_certificate "
                        + "(cert_no, batch_no, first_test_no, first_ratio, second_test_no, second_ratio, "
                        + "status, issued_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                certificate.certNo(), certificate.batchNo(),
                certificate.firstTestNo(), certificate.firstRatio(),
                certificate.secondTestNo(), certificate.secondRatio(),
                certificate.status().name(), Timestamp.from(certificate.issuedAt()));
    }

    /** 查批次当前证书（最新签发的一张，可能为失效）。 */
    public Optional<GerminationCertificate> findCertificateByBatch(String batchNo) {
        return jdbc.query(CERT_SQL + " WHERE c.batch_no = ? ORDER BY c.issued_at DESC, c.cert_no DESC LIMIT 1",
                CERT_MAPPER, batchNo).stream().findFirst();
    }

    /** 事务内锁定并查批次当前证书（先定位最新证书编号，再按主键加行锁）。 */
    public Optional<GerminationCertificate> findCertificateByBatchForUpdate(String batchNo) {
        List<String> certNos = jdbc.queryForList("SELECT cert_no FROM germination_certificate "
                + "WHERE batch_no = ? ORDER BY issued_at DESC, cert_no DESC LIMIT 1", String.class, batchNo);
        if (certNos.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query(CERT_SQL + " WHERE c.cert_no = ? FOR UPDATE",
                CERT_MAPPER, certNos.get(0)).stream().findFirst();
    }

    private static final String CERT_SQL =
            "SELECT c.cert_no, c.batch_no, c.first_test_no, c.first_ratio, "
                    + "c.second_test_no, c.second_ratio, c.status, c.issued_at "
                    + "FROM germination_certificate c";

    /** 未放行证书置为失效，返回受影响行数。 */
    public int markCertificateInvalid(String certNo) {
        return jdbc.update("UPDATE germination_certificate SET status = 'INVALID' "
                + "WHERE cert_no = ? AND status = 'VALID'", certNo);
    }

    /** 插入放行事实。 */
    public void insertRelease(BatchRelease release) {
        jdbc.update("INSERT INTO batch_release (batch_no, cert_no, released_at) VALUES (?, ?, ?)",
                release.batchNo(), release.certNo(), Timestamp.from(release.releasedAt()));
    }

    /** 查放行事实。 */
    public Optional<BatchRelease> findRelease(String batchNo) {
        return jdbc.query("SELECT batch_no, cert_no, released_at FROM batch_release WHERE batch_no = ?",
                RELEASE_MAPPER, batchNo).stream().findFirst();
    }

    /** 事务内锁定并查放行事实。 */
    public Optional<BatchRelease> findReleaseForUpdate(String batchNo) {
        return jdbc.query("SELECT batch_no, cert_no, released_at FROM batch_release "
                        + "WHERE batch_no = ? FOR UPDATE",
                RELEASE_MAPPER, batchNo).stream().findFirst();
    }
}
