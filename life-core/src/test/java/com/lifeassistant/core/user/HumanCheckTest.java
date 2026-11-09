package com.lifeassistant.core.user;

import com.lifeassistant.core.config.LifeProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HumanCheckTest {

    private final HumanCheck check = new HumanCheck(new LifeProperties(), null, 8);

    @Test
    void countsLeadingZeroBits() {
        assertThat(ProofOfWork.leadingZeroBits(new byte[]{0, 0, 1})).isEqualTo(23);
        assertThat(ProofOfWork.leadingZeroBits(new byte[]{(byte) 0x80})).isZero();
        assertThat(ProofOfWork.leadingZeroBits(new byte[]{0x0f, 0})).isEqualTo(4);
        assertThat(ProofOfWork.leadingZeroBits(new byte[]{0, 0})).isEqualTo(16);
    }

    @Test
    void acceptsSolvedChallengeAndRejectsWrongOrForgedOnes() {
        HumanCheck.Challenge c = check.issue();
        assertThat(c.difficulty()).isEqualTo(8);
        String nonce = ProofOfWork.solve(c.token(), c.difficulty());
        long now = Instant.now().getEpochSecond();

        assertThat(check.solved(c.token(), nonce, now)).isTrue();
        // 答案不对、格式不对
        String wrong = firstWrongNonce(c.token(), c.difficulty());
        assertThat(check.solved(c.token(), wrong, now)).isFalse();
        assertThat(check.solved(c.token(), "12a", now)).isFalse();
        assertThat(check.solved(c.token(), null, now)).isFalse();
        assertThat(check.solved(null, nonce, now)).isFalse();
        // 过期
        assertThat(check.solved(c.token(), nonce, now + HumanCheck.TTL.toSeconds() + 5)).isFalse();
        // 换了密钥（别的实例签的）、改了题目都不认
        assertThat(new HumanCheck(new LifeProperties(), null, 8).solved(c.token(), nonce, now)).isFalse();
        String tampered = "A" + c.token().substring(1);
        assertThat(check.solved(tampered, ProofOfWork.solve(tampered, 8), now)).isFalse();
    }

    @Test
    void everyIssuedChallengeIsSolvable() {
        long now = Instant.now().getEpochSecond();
        for (int i = 0; i < 50; i++) {
            HumanCheck.Challenge c = check.issue();
            assertThat(check.solved(c.token(), ProofOfWork.solve(c.token(), c.difficulty()), now)).as(c.token()).isTrue();
        }
    }

    @Test
    void sha256MatchesKnownVector() {
        // 前端 pow.ts 的 SHA-256 用同一组数对过
        assertThat(java.util.HexFormat.of().formatHex(ProofOfWork.sha256("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private static String firstWrongNonce(String token, int difficulty) {
        for (long n = 0; ; n++) {
            if (!ProofOfWork.solved(token, Long.toString(n), difficulty)) {
                return Long.toString(n);
            }
        }
    }
}
