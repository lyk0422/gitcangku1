package com.example.starter.repo;

import com.example.starter.domain.BucketSlot;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 时空桶容量配置数据访问。
 */
@Repository
public class CapacityConfigRepository {

    private final JdbcTemplate jdbc;

    public CapacityConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CapacityConfigPo> MAPPER = (rs, n) -> new CapacityConfigPo(
            rs.getInt("cell_x"), rs.getInt("cell_y"), rs.getLong("bucket_start"),
            rs.getInt("max_flights"), rs.getLong("updated_at"));

    private static final String COLUMNS =
            "cell_x, cell_y, bucket_start, max_flights, updated_at";

    /** 查询单个时空桶配置；不存在返回 null。 */
    public CapacityConfigPo find(int cellX, int cellY, long bucketStart) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM capacity_config "
                            + "WHERE cell_x = ? AND cell_y = ? AND bucket_start = ?",
                    MAPPER, cellX, cellY, bucketStart);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 新增或覆盖时空桶容量配置（调用方负责事务）。 */
    public void upsert(CapacityConfigPo po) {
        jdbc.update("MERGE INTO capacity_config "
                        + "(cell_x, cell_y, bucket_start, max_flights, updated_at) "
                        + "KEY (cell_x, cell_y, bucket_start) VALUES (?, ?, ?, ?, ?)",
                po.cellX(), po.cellY(), po.bucketStart(), po.maxFlights(), po.updatedAt());
    }

    /**
     * 统计某时空桶的当前全量航班占用数：仅统计序列版本等于航线当前版本的占用，
     * 同一航线在同一桶内有多个槽位时按 1 架次计（COUNT DISTINCT route_id）。
     */
    public int countCurrentOccupancy(BucketSlot bucket) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT p.route_id) FROM route_occupancy_plan p "
                        + "JOIN route r ON p.route_id = r.route_id AND p.route_version = r.version "
                        + "WHERE p.cell_x = ? AND p.cell_y = ? AND p.bucket_start = ?",
                Integer.class, bucket.cellX(), bucket.cellY(), bucket.bucketStart());
        return count == null ? 0 : count;
    }

    /**
     * 统计指定若干时空桶的当前全量航班占用，按桶分组；同一航线在同一桶内只计 1 架次。
     * 未被任何航线占用的桶不会出现在结果中（调用方按 0 处理）。
     *
     * @return 每行 Object[]{cellX:int, cellY:int, bucketStart:long, used:int}
     */
    public List<Object[]> countCurrentOccupancyGrouped(List<BucketSlot> buckets) {
        // 使用 OR 连接的参数化等值条件（H2 MySQL 兼容模式对行值构造器 IN 支持不稳定）
        StringBuilder sql = new StringBuilder(
                "SELECT p.cell_x, p.cell_y, p.bucket_start, COUNT(DISTINCT p.route_id) AS used "
                        + "FROM route_occupancy_plan p "
                        + "JOIN route r ON p.route_id = r.route_id AND p.route_version = r.version "
                        + "WHERE ");
        Object[] args = new Object[buckets.size() * 3];
        for (int i = 0; i < buckets.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("(p.cell_x = ? AND p.cell_y = ? AND p.bucket_start = ?)");
            BucketSlot bucket = buckets.get(i);
            args[3 * i] = bucket.cellX();
            args[3 * i + 1] = bucket.cellY();
            args[3 * i + 2] = bucket.bucketStart();
        }
        sql.append(" GROUP BY p.cell_x, p.cell_y, p.bucket_start");
        return jdbc.query(sql.toString(),
                (rs, n) -> new Object[]{rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getInt(4)},
                args);
    }
}
