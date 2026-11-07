package com.lifeassistant.core.favorite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 我的收藏：方案、酒店、美食、景点。同一样东西（kind + refKey）只收藏一次，重复收藏返回已有的那条。
 * 只能看、删自己的。
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private static final Set<String> KINDS = Set.of("PLAN", "HOTEL", "FOOD", "SPOT");
    private static final int MAX_PER_USER = 1000;

    private final FavoriteMapper mapper;

    /** 收藏请求：refKey 是去重键，url 是"去平台/去高德"的链接（只收 http(s)），photo 是图片地址。 */
    public record AddRequest(String kind, String refKey, String title, String subtitle, String city, String url,
                             String photo) {
    }

    /** 我的收藏，新的在前；kind、keyword 可空。 */
    public List<Favorite> list(UserCtx ctx, String kind, String keyword) {
        LambdaQueryWrapper<Favorite> q = new LambdaQueryWrapper<Favorite>().eq(Favorite::getUserId, ctx.userId());
        if (kind != null && !kind.isBlank()) {
            q.eq(Favorite::getKind, kind);
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            q.and(w -> w.like(Favorite::getTitle, k).or().like(Favorite::getSubtitle, k).or().like(Favorite::getCity, k));
        }
        return mapper.selectList(q.orderByDesc(Favorite::getCreatedAt).last("LIMIT " + MAX_PER_USER));
    }

    /** 收藏一样东西；已经收藏过就返回原来那条。 */
    public Favorite add(UserCtx ctx, AddRequest r) {
        if (r.kind() == null || !KINDS.contains(r.kind())) {
            throw bad("收藏类型不对");
        }
        String refKey = text(r.refKey(), "收藏对象", 200, true);
        Favorite existing = find(ctx, r.kind(), refKey);
        if (existing != null) {
            return existing;
        }
        if (mapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getUserId, ctx.userId())) >= MAX_PER_USER) {
            throw bad("收藏最多 " + MAX_PER_USER + " 条，先删掉一些");
        }
        Favorite row = new Favorite();
        row.setUserId(ctx.userId());
        row.setKind(r.kind());
        row.setRefKey(refKey);
        row.setTitle(text(r.title(), "名称", 120, true));
        row.setSubtitle(text(r.subtitle(), "说明", 200, false));
        row.setCity(text(r.city(), "城市", 40, false));
        row.setUrl(link(r.url()));
        row.setPhoto(link(r.photo()));
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            return find(ctx, r.kind(), refKey);
        }
        return mapper.selectById(row.getId());
    }

    /** 取消收藏（只删自己的）。 */
    public void remove(UserCtx ctx, long id) {
        Favorite row = mapper.selectById(id);
        if (row == null || !row.getUserId().equals(ctx.userId())) {
            throw BizException.notFound("收藏");
        }
        mapper.deleteById(id);
    }

    private Favorite find(UserCtx ctx, String kind, String refKey) {
        return mapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, ctx.userId())
                .eq(Favorite::getKind, kind)
                .eq(Favorite::getRefKey, refKey));
    }

    /** 链接只收 http(s)，超长或别的协议当没填，免得页面上点出 javascript: 之类的东西。 */
    private static String link(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        return (u.startsWith("https://") || u.startsWith("http://")) && u.length() <= 500 ? u : null;
    }

    private static String text(String value, String label, int max, boolean required) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            if (required) {
                throw bad("缺少" + label);
            }
            return null;
        }
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static BizException bad(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }
}
