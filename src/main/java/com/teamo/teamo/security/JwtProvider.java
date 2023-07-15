package com.teamo.teamo.security;

import com.teamo.teamo.domain.Member;
import com.teamo.teamo.repository.MemberRepository;
import com.teamo.teamo.security.token.JwtDto;
import com.teamo.teamo.type.AuthType;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.Key;
import java.util.Base64;
import java.util.Date;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class JwtProvider {

    private final String AUTHORITIES_KEY = "auth";
    private final Integer ACCESS_TOKEN_EXPIRE_TIME = 1000 * 60 * 30;
    private final Integer REFRESH_TOKEN_EXPIRE_TIME = 1000 * 60 * 60 * 24 * 7;

    @Value("ZVc3Z0g4bm5TVzRQUDJxUXBIOGRBUGtjRVg2WDl0dzVYVkMyWW")
    private String secretKey;
    private Key key;

    private final MemberRepository memberRepository;

    @PostConstruct
    private void initialize() {
        // key 인코딩
        String encodedKey = Base64.getEncoder().encodeToString(secretKey.getBytes());
        key = Keys.hmacShaKeyFor(encodedKey.getBytes());
    }

    public JwtDto generateJwtDto(OAuth2User oAuth2User) {
        Date now = new Date();
        Date accessTokenExpiresIn = new Date(now.getTime() + ACCESS_TOKEN_EXPIRE_TIME);
        Date refreshTokenExpiresIn = new Date(now.getTime() + REFRESH_TOKEN_EXPIRE_TIME);

        Member member = memberRepository.findByEmail((String) oAuth2User.getAttribute("email"))
                .orElseThrow(() -> new RuntimeException("해당 email의 유저가 존재하지 않습니다"));

        String accessToken = generateAccessToken(member.getEmail(), member.getRole(), accessTokenExpiresIn);
        String refreshToken = generateRefreshToken(member.getEmail(), refreshTokenExpiresIn);

        return JwtDto.builder()
                .grantType(AuthConst.BEARER)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .accessTokenExpiresIn(accessTokenExpiresIn.getTime())
                .build();
    }

    public String generateAccessToken(String email, AuthType role, Date accessExpireDate) {

        return Jwts.builder()
                .setSubject(email)
                .claim(AUTHORITIES_KEY, role)
                .setExpiration(accessExpireDate)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    public String generateRefreshToken(String email, Date refreshExpireDate) {
        return Jwts.builder()
                .setSubject(email)
                .setExpiration(refreshExpireDate)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    public String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AuthConst.AUTHORIZATION);

        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(AuthConst.BEARER)) {
            return bearerToken.substring(7);
        } else if (StringUtils.hasText(bearerToken) && bearerToken.equals(AuthConst.DEBUG_MODE)) {
            return AuthConst.DEBUG_MODE;
        }
        return "";
    }

    public Boolean validateAccessToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (SecurityException e) {
            log.error("올바르지 못한 토큰입니다");
        } catch (MalformedJwtException e) {
            log.error("올바르지 못한 토큰입니다");
        } catch (ExpiredJwtException e) {
            log.error("만료된 토큰입니다.");
        } catch (UnsupportedJwtException e) {
            log.error("지원되지 않는 토큰입니다.");
        } catch (IllegalArgumentException e) {
            log.error("잘못된 토큰입니다.");
        }

        return true;
    }

    public Authentication findAuthentication(String accessToken) {
        Claims claims = parseClaims(accessToken);

        List<SimpleGrantedAuthority> authorities = List.of(claims.get(AUTHORITIES_KEY))
                .stream().map(role -> new SimpleGrantedAuthority((String) role))
                .toList();
        User user = new User(claims.getSubject(), "", authorities);
        return new UsernamePasswordAuthenticationToken(user, "", authorities);
    }

    private Claims parseClaims(String accessToken) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(accessToken).getBody();
    }
}
