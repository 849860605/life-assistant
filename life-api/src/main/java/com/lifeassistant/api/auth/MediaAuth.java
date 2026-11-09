package com.lifeassistant.api.auth;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.auth.MediaTicket;
import com.lifeassistant.core.auth.TicketVersions;
import com.lifeassistant.core.user.User;
import com.lifeassistant.core.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * 地图图片、PDF 下载接口的身份：链接里有有效的下载票据就用票据里的用户，否则要求请求头里的登录 token。
 * 票据除了签名和有效期，还要版本没被作废（改过密码、被重置密码、被停用都会作废）、账号没被停用。
 */
@Component
@RequiredArgsConstructor
public class MediaAuth {

    private final MediaTicket mediaTicket;
    private final TicketVersions versions;
    private final UserService userService;
    private final AuthFacade authFacade;

    /** 给当前用户签一张票据（带上现在的版本）。 */
    public MediaTicket.Issued issue(long userId) {
        return mediaTicket.issue(userId, versions.current(userId).orElse(0));
    }

    /** 票据无效又没登录时抛未登录异常（401）。 */
    public UserCtx user(String ticket) {
        return verify(ticket).map(UserCtx::of).orElseGet(authFacade::current);
    }

    /** 校验票据，有效时返回用户 id。 */
    public Optional<Long> verify(String ticket) {
        return mediaTicket.verify(ticket)
                .filter(c -> {
                    OptionalLong now = versions.current(c.userId());
                    // Redis 读不到时只看签名和有效期，不因为它挡住看图
                    return now.isEmpty() || now.getAsLong() == c.version();
                })
                .map(MediaTicket.Claims::userId)
                .filter(this::enabled);
    }

    private boolean enabled(long userId) {
        try {
            User user = userService.get(userId);
            return user.getStatus() != null && user.getStatus() == 1;
        } catch (BizException e) {
            return false;
        }
    }
}
