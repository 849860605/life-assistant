package com.lifeassistant.api.auth;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.auth.MediaTicket;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 地图图片、PDF 下载接口的身份：链接里有有效的下载票据就用票据里的用户，否则要求请求头里的登录 token。
 */
@Component
@RequiredArgsConstructor
public class MediaAuth {

    private final MediaTicket mediaTicket;
    private final AuthFacade authFacade;

    /** 票据无效又没登录时抛未登录异常（401）。 */
    public UserCtx user(String ticket) {
        return mediaTicket.verify(ticket).map(UserCtx::of).orElseGet(authFacade::current);
    }
}
