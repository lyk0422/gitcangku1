package com.example.starter.container;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 封存容器表访问。容器状态/下次巡检时刻/版本的变更必须先通过
 * {@link #findByIdForUpdate} 锁定容器行，保证并发巡检/复核/集合变更按提交顺序裁决。
 * version 采用条件更新（乐观版本号）与行锁共同裁决：版本不符表示并发事务已先行提交。
 */
@Repository
public class SealedContainerRepository {

    private static final ContainerRowMapper ROW_MAPPER = new ContainerRowMapper();

    private final JdbcTemplate jdbc;

    public SealedContainerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建容器，初始状态 SEALED，版本为 0。
     */
    public void insert(String containerId, LocalDateTime nextInspectionAt, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO sealed_container
                            (container_id, status, next_inspection_at, version, created_at, updated_at)
                        VALUES (?, ?, ?, 0, ?, ?)
                        """,
                containerId, ContainerStatus.SEALED.name(), nextInspectionAt, now, now);
    }

    /**
     * 按业务键查询容器（不加锁）。
     */
    public Optional<SealedContainer> findById(String containerId) {
        List<SealedContainer> rows = jdbc.query(
                "SELECT * FROM sealed_container WHERE container_id = ?", ROW_MAPPER, containerId);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定容器行（SELECT ... FOR UPDATE）。
     */
    public Optional<SealedContainer> findByIdForUpdate(String containerId) {
        List<SealedContainer> rows = jdbc.query(
                "SELECT * FROM sealed_container WHERE container_id = ? FOR UPDATE",
                ROW_MAPPER, containerId);
        return rows.stream().findFirst();
    }

    /**
     * 更新下次巡检时刻并自增版本（PASS 巡检）。
     *
     * @return 更新行数；0 表示版本已被并发事务改变
     */
    public int updateNextInspectionWithVersion(String containerId, long expectedVersion,
                                               LocalDateTime nextInspectionAt, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE sealed_container
                        SET next_inspection_at = ?, version = version + 1, updated_at = ?
                        WHERE container_id = ? AND version = ?
                        """,
                nextInspectionAt, now, containerId, expectedVersion);
    }

    /**
     * 将容器转为 INSPECTION_FAILED、设置下次巡检时刻并自增版本（FAIL 巡检）。
     *
     * @return 更新行数；0 表示版本已被并发事务改变
     */
    public int markFailedWithVersion(String containerId, long expectedVersion,
                                     LocalDateTime nextInspectionAt, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE sealed_container
                        SET status = ?, next_inspection_at = ?, version = version + 1, updated_at = ?
                        WHERE container_id = ? AND version = ?
                        """,
                ContainerStatus.INSPECTION_FAILED.name(), nextInspectionAt, now,
                containerId, expectedVersion);
    }

    /**
     * 将容器恢复为 SEALED 并自增版本（双人复核完成）。
     *
     * @return 更新行数；0 表示版本已被并发事务改变
     */
    public int markSealedWithVersion(String containerId, long expectedVersion, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE sealed_container
                        SET status = ?, version = version + 1, updated_at = ?
                        WHERE container_id = ? AND version = ?
                        """,
                ContainerStatus.SEALED.name(), now, containerId, expectedVersion);
    }

    /**
     * 仅自增版本（装载/移出导致集合内容变更）。
     */
    public void bumpVersion(String containerId, LocalDateTime now) {
        jdbc.update(
                "UPDATE sealed_container SET version = version + 1, updated_at = ? WHERE container_id = ?",
                now, containerId);
    }

    /**
     * 查询全部容器（按创建顺序）。
     */
    public List<SealedContainer> findAll() {
        return jdbc.query("SELECT * FROM sealed_container ORDER BY id", ROW_MAPPER);
    }

    private static final class ContainerRowMapper implements RowMapper<SealedContainer> {
        @Override
        public SealedContainer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SealedContainer(
                    rs.getLong("id"),
                    rs.getString("container_id"),
                    ContainerStatus.valueOf(rs.getString("status")),
                    rs.getObject("next_inspection_at", LocalDateTime.class),
                    rs.getLong("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
