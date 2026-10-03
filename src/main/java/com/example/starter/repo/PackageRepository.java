package com.example.starter.repo;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.SealPackage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 封存包仓储：行级锁串行化同一包上的装包、交接、异常与重新封存操作。
 */
@Repository
public class PackageRepository {

    private static final RowMapper<SealPackage> MAPPER = (rs, n) -> new SealPackage(
            rs.getLong("id"), rs.getString("package_no"), rs.getLong("stratum_id"), rs.getString("status"),
            rs.getString("seal_no"), rs.getInt("version"),
            rs.getObject("supersedes_package_id", Long.class), rs.getObject("source_event_id", Long.class),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("handed_over_at") == null ? null : rs.getTimestamp("handed_over_at").toInstant());

    private final JdbcTemplate jdbc;

    public PackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long stratumId, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO seal_package (package_no, stratum_id, status, version, created_at)"
                            + " VALUES ('', ?, 'OPEN', 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, stratumId);
            ps.setTimestamp(2, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void assignPackageNo(long id, String packageNo) {
        jdbc.update("UPDATE seal_package SET package_no = ? WHERE id = ?", packageNo, id);
    }

    public Optional<SealPackage> findByPackageNo(String packageNo) {
        return jdbc.query("SELECT * FROM seal_package WHERE package_no = ?", MAPPER, packageNo)
                .stream().findFirst();
    }

    public Optional<SealPackage> findById(long id) {
        return jdbc.query("SELECT * FROM seal_package WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 按包编号加行级排他锁读取，串行化同一包上的并发操作。
     */
    public Optional<SealPackage> findByPackageNoForUpdate(String packageNo) {
        return jdbc.query("SELECT * FROM seal_package WHERE package_no = ? FOR UPDATE", MAPPER, packageNo)
                .stream().findFirst();
    }

    public void addItem(long packageId, long sampleId, Instant addedAt) {
        jdbc.update("INSERT INTO package_item (package_id, sample_id, added_at) VALUES (?, ?, ?)",
                packageId, sampleId, Timestamp.from(addedAt));
    }

    public int countItems(long packageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM package_item WHERE package_id = ?", Integer.class, packageId);
        return count == null ? 0 : count;
    }


    public void copyItems(long fromPackageId, long toPackageId, Instant addedAt) {
        jdbc.update("INSERT INTO package_item (package_id, sample_id, added_at)"
                        + " SELECT ?, sample_id, ? FROM package_item WHERE package_id = ?",
                toPackageId, Timestamp.from(addedAt), fromPackageId);
    }

    public void markHandedOver(long id, String sealNo, Instant handedOverAt) {
        jdbc.update("UPDATE seal_package SET status = 'HANDED_OVER', seal_no = ?, handed_over_at = ?,"
                + " version = version + 1 WHERE id = ?", sealNo, Timestamp.from(handedOverAt), id);
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE seal_package SET status = ?, version = version + 1 WHERE id = ?", status, id);
    }

    public void bumpVersion(long id) {
        jdbc.update("UPDATE seal_package SET version = version + 1 WHERE id = ?", id);
    }

    public void setLineage(long id, long supersedesPackageId, long sourceEventId) {
        jdbc.update("UPDATE seal_package SET supersedes_package_id = ?, source_event_id = ? WHERE id = ?",
                supersedesPackageId, sourceEventId, id);
    }
}