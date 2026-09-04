package com.seatlock.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;
    private final String issuer;

    public JwtService(@Value("${seatlock.jwt.secret}") String secret,
                      @Value("${seatlock.jwt.ttl}") Duration ttl,
                      @Value("${seatlock.jwt.issuer}") String issuer) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256 requires >= 256 bits of key material. Failing at startup with a
        // clear message beats discovering a weak secret in production.
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "seatlock.jwt.secret must be at least 32 bytes; got " + keyBytes.length);
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.ttl = ttl;
        this.issuer = issuer;
    }

    public String issue(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.email())
                .issuer(issuer)
                .claim("uid", user.id())
                .claim("role", user.authority())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * Returns the token claims, or empty if the token is malformed, unsigned by
     * us, or expired. Callers cannot accidentally treat a bad token as valid,
     * because there is no path that returns claims without verification.
     */
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
