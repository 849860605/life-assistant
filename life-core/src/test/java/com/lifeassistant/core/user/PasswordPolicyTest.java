package com.lifeassistant.core.user;

import com.lifeassistant.common.error.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 密码规则：长度、字母加数字、前后空格；用户名规则。 */
class PasswordPolicyTest {

    @Test
    void acceptsLettersAndDigitsOfEnoughLength() {
        assertThatCode(() -> PasswordPolicy.check("abc12345")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.check("星络Xinglo2026!")).doesNotThrowAnyException();
    }

    @Test
    void rejectsWeakPasswords() {
        assertThatThrownBy(() -> PasswordPolicy.check("abc1234")).isInstanceOf(BizException.class).hasMessageContaining("至少 8 位");
        assertThatThrownBy(() -> PasswordPolicy.check("abcdefgh")).hasMessageContaining("字母和数字");
        assertThatThrownBy(() -> PasswordPolicy.check("12345678")).hasMessageContaining("字母和数字");
        assertThatThrownBy(() -> PasswordPolicy.check(" abc12345")).hasMessageContaining("空格");
        assertThatThrownBy(() -> PasswordPolicy.check(null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("a1".repeat(33))).hasMessageContaining("最多");
    }

    @Test
    void checksUsernames() {
        assertThatCode(() -> UserService.checkUsername("sunxiaoyan")).doesNotThrowAnyException();
        assertThatThrownBy(() -> UserService.checkUsername("ab")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> UserService.checkUsername("有空格 的")).isInstanceOf(BizException.class);
    }
}
