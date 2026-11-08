package com.lifeassistant.core.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** 和风天气 JWT：私钥各种写法都能读；签出来的 Token 格式符合文档、公钥能验过。 */
class QWeatherJwtTest {

    @Test
    void signsTokenThatVerifiesWithPublicKey() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String body = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";

        // PEM、只有中间那段、带空格换行都能读出同一把私钥
        assertThat(QWeatherProvider.privateKey(pem).getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
        assertThat(QWeatherProvider.privateKey(body).getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
        assertThat(QWeatherProvider.privateKey(pem.replace("\n", " ")).getEncoded()).isEqualTo(pair.getPrivate().getEncoded());

        String token = QWeatherProvider.sign("Q12345ABCD", "ABCDE23456", "ABCDE12345", pem, 1_703_912_430L);
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(token).doesNotContain("=").doesNotContain("+").doesNotContain("/");

        ObjectMapper json = new ObjectMapper();
        JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("alg").asText()).isEqualTo("EdDSA");
        assertThat(header.path("kid").asText()).isEqualTo("ABCDE12345");
        assertThat(payload.path("iss").asText()).isEqualTo("Q12345ABCD");
        assertThat(payload.path("sub").asText()).isEqualTo("ABCDE23456");
        assertThat(payload.path("iat").asLong()).isEqualTo(1_703_912_400L);
        assertThat(payload.path("exp").asLong() - payload.path("iat").asLong()).isLessThanOrEqualTo(86_400L);

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }
}
