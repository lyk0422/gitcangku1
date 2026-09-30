package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Responses.CredentialView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源凭据服务测试：登记/查询/撤销主流程、(resourceKey, version) 唯一与内容冲突、
 * 撤销不可逆、commandKey 幂等语义与失败不占键。时间断言全部基于可控时钟。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class CredentialServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T11:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task_dispatches");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM command_keys");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private CredentialView register(String resourceKey, String version, String type,
                                    String region, Instant expiresAt) {
        return service.registerCredential(new CredentialRegisterRequest(
                key(), resourceKey, version, type, region, expiresAt));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void register_query_and_revoke_mainFlow() {
        CredentialView v1 = register("R", "v1", "AWS_AK", "A", EXPIRES);
        assertThat(v1.resourceKey()).isEqualTo("R");
        assertThat(v1.version()).isEqualTo("v1");
        assertThat(v1.credentialType()).isEqualTo("AWS_AK");
        assertThat(v1.region()).isEqualTo("A");
        assertThat(v1.status()).isEqualTo("ACTIVE");
        assertThat(v1.expiresAt()).isEqualTo(EXPIRES);
        assertThat(v1.revokedAt()).isNull();

        // 同资源登记新版本
        CredentialView v2 = register("R", "v2", "AWS_AK", "A",
                Instant.parse("2026-10-01T18:00:00Z"));
        List<CredentialView> all = service.listCredentials("R");
        assertThat(all).extracting(CredentialView::version).containsExactly("v1", "v2");
        assertThat(all).extracting(CredentialView::status).containsExactly("ACTIVE", "ACTIVE");

        // 撤销 v1 不可逆，v2 不受影响
        CredentialView revoked = service.revokeCredential("R", "v1", "alice",
                new CredentialRevokeRequest(key()));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isEqualTo(T0);
        List<CredentialView> after = service.listCredentials("R");
        assertThat(after.get(0).status()).isEqualTo("REVOKED");
        assertThat(after.get(1).status()).isEqualTo("ACTIVE");

        // 重复撤销幂等返回当前视图
        CredentialView again = service.revokeCredential("R", "v1", "alice",
                new CredentialRevokeRequest(key()));
        assertThat(again).isEqualTo(revoked);
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_credentials WHERE resource_key='R' AND status='ACTIVE'",
                Integer.class);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void register_sameVersion_sameContent_idempotent_acrossCommandKeys() {
        CredentialView first = register("R", "v1", "AWS_AK", "A", EXPIRES);
        // 不同 commandKey、同资源同版本同内容：幂等返回首次记录
        CredentialView replay = register("R", "v1", "AWS_AK", "A", EXPIRES);
        assertThat(replay).isEqualTo(first);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_credentials WHERE resource_key='R'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void register_sameVersion_differentContent_conflict() {
        register("R", "v1", "AWS_AK", "A", EXPIRES);
        assertApiStatus(() -> register("R", "v1", "AWS_AK", "B", EXPIRES), HttpStatus.CONFLICT);
        assertApiStatus(() -> register("R", "v1", "AZURE_SP", "A", EXPIRES), HttpStatus.CONFLICT);
        assertApiStatus(() -> register("R", "v1", "AWS_AK", "A",
                Instant.parse("2026-10-01T13:00:00Z")), HttpStatus.CONFLICT);
    }

    @Test
    void register_validation_and_notFound() {
        assertApiStatus(() -> service.registerCredential(new CredentialRegisterRequest(
                key(), " ", "v1", "T", "A", EXPIRES)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.registerCredential(new CredentialRegisterRequest(
                key(), "R", " ", "T", "A", EXPIRES)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.registerCredential(new CredentialRegisterRequest(
                key(), "R", "v1", " ", "A", EXPIRES)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.registerCredential(new CredentialRegisterRequest(
                key(), "R", "v1", "T", " ", EXPIRES)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.registerCredential(new CredentialRegisterRequest(
                null, "R", "v1", "T", "A", EXPIRES)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.registerCredential(new CredentialRegisterRequest(
                key(), "R", "v1", "T", "A", null)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.revokeCredential("R", "v9", "alice",
                new CredentialRevokeRequest(key())), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.revokeCredential("R9", "v1", "alice",
                new CredentialRevokeRequest(key())), HttpStatus.NOT_FOUND);
    }

    @Test
    void register_commandKey_idempotency() {
        String commandKey = key();
        CredentialRegisterRequest req = new CredentialRegisterRequest(
                commandKey, "R", "v1", "AWS_AK", "A", EXPIRES);
        CredentialView first = service.registerCredential(req);
        CredentialView replay = service.registerCredential(req);
        assertThat(replay).isEqualTo(first);
        // 同键改参 → 409
        assertApiStatus(() -> service.registerCredential(new CredentialRegisterRequest(
                commandKey, "R", "v2", "AWS_AK", "A", EXPIRES)), HttpStatus.CONFLICT);
        // 同键跨操作（撤销）→ 409
        assertApiStatus(() -> service.revokeCredential("R", "v1", "alice",
                new CredentialRevokeRequest(commandKey)), HttpStatus.CONFLICT);
    }

    @Test
    void revoke_failureDoesNotConsumeCommandKey() {
        register("R", "v1", "AWS_AK", "A", EXPIRES);
        String commandKey = key();
        // 撤销不存在的版本：404 回滚不占键
        assertApiStatus(() -> service.revokeCredential("R", "v9", "alice",
                new CredentialRevokeRequest(commandKey)), HttpStatus.NOT_FOUND);
        CredentialView revoked = service.revokeCredential("R", "v1", "alice",
                new CredentialRevokeRequest(commandKey));
        assertThat(revoked.status()).isEqualTo("REVOKED");
    }
}
