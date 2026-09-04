package com.seatlock;

import com.seatlock.security.AuthUser;
import com.seatlock.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests: no Spring context, no containers, milliseconds to run. */
@DisplayName("JWT issuing and verification")
class JwtServiceTest {

    private static final String SECRET = "a-test-secret-long-enough-for-hs256-0123456789";

    private final JwtService jwt = new JwtService(SECRET, Duration.ofHours(1), "seatlock");
    private final AuthUser user = new AuthUser(42L, "someone@test.local", null, "ROLE_USER");

    @Test
    @DisplayName("A freshly issued token verifies and carries the user id")
    void roundTrip() {
        var claims = jwt.parse(jwt.issue(user)).orElseThrow();

        assertThat(claims.getSubject()).isEqualTo("someone@test.local");
        assertThat(claims.get("uid", Number.class).longValue()).isEqualTo(42L);
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("A token signed with a different key is rejected")
    void foreignSignatureIsRejected() {
        JwtService other = new JwtService("a-completely-different-secret-0123456789xyz",
                Duration.ofHours(1), "seatlock");

        assertThat(jwt.parse(other.issue(user)))
                .as("a token this service did not sign must never verify")
                .isEmpty();
    }

    @Test
    @DisplayName("An already-expired token is rejected")
    void expiredTokenIsRejected() {
        JwtService instant = new JwtService(SECRET, Duration.ofSeconds(-1), "seatlock");
        assertThat(jwt.parse(instant.issue(user))).isEmpty();
    }

    @Test
    @DisplayName("A token from another issuer is rejected")
    void foreignIssuerIsRejected() {
        JwtService elsewhere = new JwtService(SECRET, Duration.ofHours(1), "some-other-service");
        assertThat(jwt.parse(elsewhere.issue(user)))
                .as("sharing a secret must not mean sharing an audience")
                .isEmpty();
    }

    @Test
    @DisplayName("Garbage input is rejected rather than thrown from")
    void malformedTokensAreRejected() {
        assertThat(jwt.parse("not.a.token")).isEmpty();
        assertThat(jwt.parse("")).isEmpty();
    }

    @Test
    @DisplayName("A short secret fails at startup, not at first login")
    void weakSecretIsRefusedUpFront() {
        assertThatThrownBy(() -> new JwtService("too-short", Duration.ofHours(1), "seatlock"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
