package com.lifeassistant.core.user;

import com.lifeassistant.common.error.BizException;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordCipherTest {

    private static final PasswordCipher CIPHER = new PasswordCipher(PasswordCipher.generate(), null);

    /** 和前端一样：用公钥的 n、e 加密"时间\n密码"。 */
    private static String seal(PasswordCipher cipher, long time, String password) throws Exception {
        return sealRaw(cipher, time + "\n" + password);
    }

    private static String sealRaw(PasswordCipher cipher, String plain) throws Exception {
        PasswordCipher.LoginKey key = cipher.loginKey();
        PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(new BigInteger(key.n(), 16), new BigInteger(key.e(), 16)));
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(Cipher.ENCRYPT_MODE, pub, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT));
        return Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decryptsPasswordSealedWithinFiveMinutes() throws Exception {
        long now = System.currentTimeMillis();
        assertThat(CIPHER.decrypt(seal(CIPHER, now - 3000, "abc12345"), now)).isEqualTo("abc12345");
        // 密码里有换行、中文、64 个四字节字符也能装下
        String longest = "😀".repeat(64);
        assertThat(CIPHER.decrypt(seal(CIPHER, now, longest), now)).isEqualTo(longest);
        assertThat(CIPHER.decrypt(seal(CIPHER, now, "a\nb中文1"), now)).isEqualTo("a\nb中文1");
    }

    @Test
    void rejectsExpiredForgedOrForeignCiphertext() throws Exception {
        long now = System.currentTimeMillis();
        String old = seal(CIPHER, now - PasswordCipher.TTL.toMillis() - 1000, "abc12345");
        assertThatThrownBy(() -> CIPHER.decrypt(old, now)).isInstanceOf(BizException.class);
        // 另一把密钥加密的（比如服务重启前领的公钥）
        PasswordCipher other = new PasswordCipher(PasswordCipher.generate(), null);
        String foreign = seal(other, now, "abc12345");
        assertThatThrownBy(() -> CIPHER.decrypt(foreign, now)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> CIPHER.decrypt("bm90IHJzYQ==", now)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> CIPHER.decrypt("%%%", now)).isInstanceOf(BizException.class);
        // 没带时间、时间不是数字
        String noTime = sealRaw(CIPHER, "abc12345");
        assertThatThrownBy(() -> CIPHER.decrypt(noTime, now)).isInstanceOf(BizException.class);
        String badTime = sealRaw(CIPHER, "x\nabc12345");
        assertThatThrownBy(() -> CIPHER.decrypt(badTime, now)).isInstanceOf(BizException.class);
    }

    @Test
    void rejectsPlainPassword() {
        assertThatThrownBy(() -> CIPHER.open("abc12345")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> CIPHER.open(null)).isInstanceOf(BizException.class);
    }
}
