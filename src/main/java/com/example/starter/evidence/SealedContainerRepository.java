package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 封存容器表访问。容器行锁（SELECT ... FOR UPDATE）是巡检、复核与装载并发裁决的串行点。
 */
@Repository
public class SealedContainerRepository {

    private static final ContainerRowMapper ROW_MAPPER = new ContainerRowMapper();

    private final JdbcTemplate jdbc;

    public SealedContainerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建容器，初始状态 SEALED，版本号 0。
     */
    public void insert(String containerKey, String custodianId, LocalDateTime nextInspectionAt,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO sealed_container
                            (container_key, custodian_id, status, next_inspection_at, version,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, 0, ?, ?)
                        """,
                containerKey, custodianId, ContainerStatus.SEALED.name(), nextInspectionAt, now, now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景。
     */
    public Optional<SealedContainer> findByKey(String containerKey) {
        List<SealedContainer> rows = jdbc.query(
                "SELECT * FROM sealed_container WHERE container_key = ?", ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定容器行，用于巡检、复核、装载等一切容器写操作。
     */
    public Optional<SealedContainer> findByKeyForUpdate(String containerKey) {
        List<SealedContainer> rows = jdbc.query(
                "SELECT * FROM sealed_container WHERE container_key = ? FOR UPDATE",
                ROW_MAPPER, containerKey);
        return rows.stream().findFirst();
    }

    /**
     * 巡检通过：仅推进下次巡检时刻并递增版本，状态保持 SEALED（不改写历史 FAIL 之外的任何记录）。
     */
    public void advanceInspection(String containerKey, LocalDateTime nextInspectionAt,
                                  LocalDateTime now) {
        jdbc.update("""
                        UPDATE sealed_container
                        SET next_inspection_at = ?, version = version + 1, updated_at = ?
                        WHERE container_key = ?
                        """,
                nextInspectionAt, now, containerKey);
    }

    /**
     * 巡检失败：转 INSPECTION_FAILED、推进下次巡检时刻并递增版本。
     */
    public void markFailed(String containerKey, LocalDateTime nextInspectionAt, LocalDateTime now) {
        jdbc.update("""
                        UPDATE sealed_container
                        SET status = ?, next_inspection_at = ?, version = version + 1, updated_at = ?
                        WHERE container_key = ?
                        """,
                ContainerStatus.INSPECTION_FAILED.name(), nextInspectionAt, now, containerKey);
    }

    /**
     * 双人复核完成：条件恢复为 SEALED 并递增版本；仅当仍为 INSPECTION_FAILED 时生效。
     *
     * @return 是否更新成功（false 表示已被并发复核恢复）
     */
    public boolean markSealed(String containerKey, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE sealed_container
                        SET status = ?, version = version + 1, updated_at = ?
                        WHERE container_key = ? AND status = ?
                        """,
                ContainerStatus.SEALED.name(), now, containerKey,
                ContainerStatus.INSPECTION_FAILED.name());
        return updated == 1;
    }

    private static final class ContainerRowMapper implements RowMapper<SealedContainer> {
        @Override
        public SealedContainer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SealedContainer(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    rs.getString("custodian_id"),
                    ContainerStatus.valueOf(rs.getString("status")),
                    rs.getObject("next_inspection_at", LocalDateTime.class),
                    rs.getLong("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
