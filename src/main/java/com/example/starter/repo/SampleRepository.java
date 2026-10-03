package com.example.starter.repo;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.Sample;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 样本仓储：编号全项目唯一，入包通过条件更新保证并发下最多一个包成功。
 */
@Repository
public class SampleRepository {

    private static final RowMapper<Sample> MAPPER = (rs, n) -> new Sample(
            rs.getLong("id"), rs.getString("sample_no"), rs.getLong("stratum_id"), rs.getString("grid_ref"),
            rs.getTimestamp("collected_at").toInstant(),
            rs.getObject("package_id", Long.class), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public SampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String sampleNo, long stratumId, String gridRef, Instant collectedAt, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO sample (sample_no, stratum_id, grid_ref, collected_at, status, created_at)"
                            + " VALUES (?, ?, ?, ?, 'COLLECTED', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sampleNo);
            ps.setLong(2, stratumId);
            ps.setString(3, gridRef);
            ps.setTimestamp(4, Timestamp.from(collectedAt));
            ps.setTimestamp(5, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Sample> findBySampleNo(String sampleNo) {
        return jdbc.query("SELECT * FROM sample WHERE sample_no = ?", MAPPER, sampleNo).stream().findFirst();
    }

    public Optional<Sample> findById(long id) {
        return jdbc.query("SELECT * FROM sample WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 仅当样本未入包时占用归属，返回受影响行数；并发下最多一个包更新成功。
     */
    public int assignToPackage(long sampleId, long packageId) {
        return jdbc.update("UPDATE sample SET package_id = ?, status = 'IN_PACKAGE'"
                + " WHERE id = ? AND package_id IS NULL", packageId, sampleId);
    }

    public void updateStatusByPackage(long packageId, String status) {
        jdbc.update("UPDATE sample SET status = ? WHERE package_id = ?", status, packageId);
    }

    public void movePackage(long fromPackageId, long toPackageId) {
        jdbc.update("UPDATE sample SET package_id = ? WHERE package_id = ?", toPackageId, fromPackageId);
    }

    public List<Sample> findByPackageId(long packageId) {
        return jdbc.query("SELECT * FROM sample WHERE package_id = ? ORDER BY id", MAPPER, packageId);
    }
}