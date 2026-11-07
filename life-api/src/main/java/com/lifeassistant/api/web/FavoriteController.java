package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.favorite.Favorite;
import com.lifeassistant.core.favorite.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 我的收藏（需要登录）：列表（按类型、关键词）、收藏、取消收藏。 */
@RestController
@RequestMapping("/api/v1/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final AuthFacade authFacade;
    private final FavoriteService favorites;

    /** GET /api/v1/favorites?kind=&q=：我的收藏，新的在前。 */
    @GetMapping
    public ApiResponse<List<Favorite>> list(@RequestParam(required = false) String kind,
                                            @RequestParam(required = false) String q) {
        return ApiResponse.ok(favorites.list(authFacade.current(), kind, q));
    }

    /** POST /api/v1/favorites：收藏；重复收藏返回已有的那条。 */
    @PostMapping
    public ApiResponse<Favorite> add(@RequestBody FavoriteService.AddRequest request) {
        return ApiResponse.ok(favorites.add(authFacade.current(), request));
    }

    /** DELETE /api/v1/favorites/{id}：取消收藏。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable long id) {
        favorites.remove(authFacade.current(), id);
        return ApiResponse.ok();
    }
}
