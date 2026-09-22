package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * H2 数据库边界测试：唯一约束与事务回滚由真实 H2（MODE=MySQL）验证，不使用 mock。
 */
class DatabaseConstraintTest extends IntegrationTestBase {

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void idempotencyKeyPrimaryKeyIsEnforced() {
        insertIdempotencyKey("db-1");
        // 同一 requestId 直接再次插入：H2 主键约束拒绝。
        assertThrows(DuplicateKeyException.class, () -> insertIdempotencyKey("db-1"));
        assertEquals(1, countRows("idempotency_keys"));
    }

    @Test
    void zonePrimaryKeyIsEnforced() {
        insertZone("zone-a");
        assertThrows(DuplicateKeyException.class, () -> insertZone("zone-a"));
        assertEquals(1, countRows("zones"));
    }

    @Test
    void routePrimaryKeyIsEnforced() {
        insertRoute("route-a");
        assertThrows(DuplicateKeyException.class, () -> insertRoute("route-a"));
        assertEquals(1, countRows("routes"));
    }

    @Test
    void transactionRollsBackKeyAndBusinessRowsTogether() {
        // 同一事务内先占键再制造失败：回滚后键与业务行都不存在，即失败不占键。
        assertThrows(IllegalStateException.class, () -> transactionTemplate.execute(status -> {
            insertIdempotencyKey("db-2");
            insertZone("zone-b");
            throw new IllegalStateException("模拟业务失败");
        }));
        assertEquals(0, countRows("idempotency_keys"));
        assertEquals(0, countRows("zones"));
    }

    private void insertIdempotencyKey(String requestId) {
        jdbc.update("INSERT INTO idempotency_keys (request_id, action, fingerprint, response_body,"
                        + " created_at) VALUES (?, 'TEST', 'fp', NULL, ?)",
                requestId, Timestamp.from(Instant.now()));
    }

    private void insertZone(String zoneId) {
        jdbc.update("INSERT INTO zones (zone_id, min_x, min_y, max_x, max_y, active, created_at,"
                        + " revoked_at) VALUES (?, 0, 0, 10, 10, TRUE, ?, NULL)",
                zoneId, Timestamp.from(Instant.now()));
    }

    private void insertRoute(String routeId) {
        jdbc.update("INSERT INTO routes (route_id, version, points, created_at, updated_at)"
                        + " VALUES (?, 1, '[]', ?, ?)",
                routeId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }
}
