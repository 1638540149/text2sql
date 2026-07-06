package com.text2sql.security;

import com.text2sql.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final AppProperties properties;
    private final SecretKey key;

    public JwtService(AppProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generate(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(user.getUsername())
            .claim("uid", user.getId())
            .claim("role", user.getRole())
            .claim("displayName", user.getDisplayName())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(properties.getJwtExpireMinutes() * 60)))
            .signWith(key)
            .compact();
    }

    public String username(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
    }
}
