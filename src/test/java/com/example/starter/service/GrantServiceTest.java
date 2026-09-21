package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.common.TimeSupport;
import com.example.starter.domain.Grant;
import com.example.starter.support.TestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权：创建校验、撤销主流程与幂等边界。
 */
class GrantServiceTest {

    private TestFixture fx;

    @BeforeEach
    void setUp() {
        fx = new TestFixture();
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static ApiException assertStatus(HttpStatus status, Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        assertEquals(status, e.status(), e.getMessage());
        return e;
    }

    private Grant createGrant() {
        return fx.grantService.create(TestFixture.CHANNEL, TestFixture.ASSET_60S,
                TimeSupport.dayStart(TestFixture.DAY), TimeSupport.dayEnd(TestFixture.DAY));
    }

    @Test
    void createGrantSucceeds() {
        Grant grant = createGrant();
        assertFalse(grant.revoked());
        assertEquals(TestFixture.CHANNEL, grant.channelId());
        assertEquals(grant, fx.grantService.get(grant.id()));
    }

    @Test
    void createGrantWithInvalidIntervalRejected() {
        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, () -> fx.grantService.create(
                TestFixture.CHANNEL, TestFixture.ASSET_60S,
                TimeSupport.dayEnd(TestFixture.DAY), TimeSupport.dayStart(TestFixture.DAY)));
    }

    @Test
    void createGrantWithUnknownChannelRejected() {
        assertStatus(HttpStatus.NOT_FOUND, () -> fx.grantService.create(
                "no-such-channel", TestFixture.ASSET_60S,
                TimeSupport.dayStart(TestFixture.DAY), TimeSupport.dayEnd(TestFixture.DAY)));
    }

    @Test
    void createGrantWithUnknownAssetRejected() {
        assertStatus(HttpStatus.NOT_FOUND, () -> fx.grantService.create(
                TestFixture.CHANNEL, "no-such-asset",
                TimeSupport.dayStart(TestFixture.DAY), TimeSupport.dayEnd(TestFixture.DAY)));
    }

    @Test
    void revokeGrant() {
        Grant grant = createGrant();
        Grant revoked = fx.grantService.revoke(grant.id(), rid());
        assertTrue(revoked.revoked());
        assertTrue(fx.grantService.get(grant.id()).revoked());
    }

    @Test
    void revokeUnknownGrantRejected() {
        assertStatus(HttpStatus.NOT_FOUND, () -> fx.grantService.revoke("no-such-grant", rid()));
    }

    @Test
    void revokeIdempotentReplay() {
        Grant grant = createGrant();
        String requestId = rid();
        Grant first = fx.grantService.revoke(grant.id(), requestId);
        Grant replay = fx.grantService.revoke(grant.id(), requestId);
        assertEquals(first, replay);
        assertTrue(replay.revoked());
    }

    @Test
    void revokeWithNewRequestIdOnRevokedGrantSucceeds() {
        Grant grant = createGrant();
        fx.grantService.revoke(grant.id(), rid());
        // 已撤销的授权用新 requestId 再次撤销：幂等成功
        Grant again = fx.grantService.revoke(grant.id(), rid());
        assertTrue(again.revoked());
    }

    @Test
    void failedRevokeDoesNotConsumeRequestId() {
        String requestId = rid();
        // 授权不存在，第一次失败
        assertStatus(HttpStatus.NOT_FOUND,
                () -> fx.grantService.revoke("no-such-grant", requestId));
        // 创建授权后同一 requestId 可成功
        Grant grant = createGrant();
        Grant revoked = fx.grantService.revoke(grant.id(), requestId);
        assertTrue(revoked.revoked());
    }

    @Test
    void blankRequestIdRejected() {
        Grant grant = createGrant();
        assertStatus(HttpStatus.BAD_REQUEST, () -> fx.grantService.revoke(grant.id(), " "));
    }
}
