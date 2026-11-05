package io.agentscope.platform.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** 用仓库内的 dev 私钥构造一个已过期但签名有效的 access token。 */
final class ExpiredTokens {

    private ExpiredTokens() {}

    static String build(int port) throws Exception {
        String pem = new String(
                AuthOidcIntegrationTest.class.getResourceAsStream("/keys/dev-rsa.pem").readAllBytes(),
                StandardCharsets.UTF_8);
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("1")
                .issuer("http://localhost:" + port)
                .issueTime(new Date(System.currentTimeMillis() - 7_200_000))
                .expirationTime(new Date(System.currentTimeMillis() - 3_600_000))
                .jwtID(UUID.randomUUID().toString())
                .claim("scope", List.of("openid", "profile"))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID("auth-signing-key-1")
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
