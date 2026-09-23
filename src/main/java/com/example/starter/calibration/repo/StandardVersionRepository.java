package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.StandardVersion;
import com.example.starter.calibration.model.StandardVersionStatus;

/**
 * 标准器版本持久化与血缘遍历。血缘为 parent_version_id 构成的树：
 * 向下闭包用于失效影响计算；插入时由服务层保证无环且子级窗口不超出父级窗口。
 */
@Repository
public class StandardVersionRepository {

    private static final RowMapper<StandardVersion> MAPPER = (rs, rowNum) -> new StandardVersion(
            rs.getLong("id"),
            rs.getString("version_key"),
            rs.getString("standard_id"),
            (Long) rs.getObject("parent_version_id"),
            JdbcTimes.fromDb(rs.getObject("valid_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("valid_to", LocalDateTime.class)),
            rs.getString("certificate_no"),
            StandardVersionStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public StandardVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入标准器版本并返回生成 ID。
     */
    public long insert(StandardVersion version) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO standard_version "
                            + "(version_key, standard_id, parent_version_id, valid_from, valid_to, "
                            + "certificate_no, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, version.versionKey());
            ps.setString(2, version.standardId());
            if (version.parentVersionId() == null) {
                ps.setObject(3, null);
            } else {
                ps.setLong(3, version.parentVersionId());
            }
            ps.setObject(4, JdbcTimes.toDb(version.validFrom()));
            ps.setObject(5, JdbcTimes.toDb(version.validTo()));
            ps.setString(6, version.certificateNo());
            ps.setString(7, version.status().name());
            ps.setObject(8, JdbcTimes.toDb(version.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按自增 ID 查询（不加锁）。
     */
    public Optional<StandardVersion> findById(long id) {
        return jdbc.query("SELECT * FROM standard_version WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按自增 ID 查询并加行锁（须在事务内调用）。
     */
    public Optional<StandardVersion> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM standard_version WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按业务键查询（不加锁）。
     */
    public Optional<StandardVersion> findByVersionKey(String versionKey) {
        return jdbc.query("SELECT * FROM standard_version WHERE version_key = ?", MAPPER, versionKey)
                .stream().findFirst();
    }

    /**
     * 按业务键查询并加行锁（须在事务内调用），用于测量提交时固化标准器版本状态。
     */
    public Optional<StandardVersion> findByVersionKeyForUpdate(String versionKey) {
        return jdbc.query("SELECT * FROM standard_version WHERE version_key = ? FOR UPDATE",
                MAPPER, versionKey).stream().findFirst();
    }

    /**
     * 查询全部版本（按 standard_id、id 稳定排序）。
     */
    public List<StandardVersion> findAll() {
        return jdbc.query("SELECT * FROM standard_version ORDER BY standard_id, id", MAPPER);
    }

    /**
     * 沿血缘向下计算闭包：包含根版本自身，以及在 effectiveFrom 时刻及以后仍可能被使用
     * （valid_to &gt; effectiveFrom）的全部子孙版本。按 (standard_id, id) 稳定排序。
     * 结果同时包含版本列表与父指针映射，供服务层计算最短血缘路径。
     */
    public Closure loadDownwardClosure(long rootId, Instant effectiveFrom) {
        List<StandardVersion> all = findAll();
        Map<Long, List<StandardVersion>> children = new HashMap<>();
        for (StandardVersion version : all) {
            if (version.parentVersionId() != null) {
                children.computeIfAbsent(version.parentVersionId(), k -> new ArrayList<>()).add(version);
            }
        }
        StandardVersion root = all.stream().filter(v -> v.id() == rootId).findFirst().orElse(null);
        if (root == null) {
            return new Closure(List.of(), Map.of());
        }
        List<StandardVersion> reached = new ArrayList<>();
        Map<Long, Long> parents = new HashMap<>();
        List<StandardVersion> queue = new ArrayList<>();
        queue.add(root);
        int head = 0;
        while (head < queue.size()) {
            StandardVersion current = queue.get(head++);
            reached.add(current);
            for (StandardVersion child : children.getOrDefault(current.id(), List.of())) {
                parents.put(child.id(), current.id());
                if (child.validTo().isAfter(effectiveFrom)) {
                    queue.add(child);
                }
            }
        }
        reached.sort((a, b) -> {
            int byStandard = a.standardId().compareTo(b.standardId());
            return byStandard != 0 ? byStandard : Long.compare(a.id(), b.id());
        });
        return new Closure(reached, parents);
    }

    /**
     * 将一批标准器版本标记为 INVALID（须在失效激活事务内调用）。
     */
    public void markInvalid(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(", ", ids.stream().map(id -> "?").toList());
        jdbc.update("UPDATE standard_version SET status = 'INVALID' WHERE id IN (" + placeholders + ")",
                ids.toArray());
    }

    /**
     * 血缘向下闭包：稳定排序的版本列表与每个节点的父版本 ID 映射。
     *
     * @param versions 闭包内版本（含根），按 (standardId, id) 排序
     * @param parents  节点 ID 到其父版本 ID 的映射（根不在映射中）
     */
    public record Closure(List<StandardVersion> versions, Map<Long, Long> parents) {
    }
}
