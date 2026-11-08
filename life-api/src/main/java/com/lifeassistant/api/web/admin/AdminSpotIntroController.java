package com.lifeassistant.api.web.admin;

import com.lifeassistant.api.auth.AdminContext;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.user.AdminUser;
import com.lifeassistant.skill.travel.ticket.SpotIntro;
import com.lifeassistant.skill.travel.ticket.SpotIntroService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台的景点简介：AI 根据资料整理的简介有错时在这里改；改过的不再自动重写，删掉的下次有人打开时重新生成。
 * 都记操作日志。
 */
@RestController
@RequestMapping("/admin/api/v1/spot-intros")
@RequiredArgsConstructor
public class AdminSpotIntroController {

    private final SpotIntroService intros;
    private final AdminContext adminContext;

    /** 改简介的请求：intro 为空表示这个景点不显示简介。 */
    public record EditRequest(String intro) {
    }

    /** GET /admin/api/v1/spot-intros?keyword=岳麓：按景点名或城市搜，最近更新的在前，最多 200 条。 */
    @GetMapping
    public ApiResponse<List<SpotIntro>> list(@RequestParam(required = false) String keyword) {
        adminContext.current();
        return ApiResponse.ok(intros.list(keyword, 200));
    }

    /** PUT /admin/api/v1/spot-intros/{id}：改简介。 */
    @PutMapping("/{id}")
    public ApiResponse<SpotIntro> edit(@PathVariable long id, @RequestBody EditRequest request) {
        AdminUser me = adminContext.current();
        SpotIntro s = intros.edit(id, request.intro());
        adminContext.log(me.getId(), "EDIT_SPOT_INTRO", s.getCity() + s.getName() + "：" + (s.getIntro() == null ? "不显示简介" : s.getIntro()));
        return ApiResponse.ok(s);
    }

    /** DELETE /admin/api/v1/spot-intros/{id}：删掉，下次有人打开这个景点时重新生成。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        AdminUser me = adminContext.current();
        SpotIntro s = intros.delete(id);
        if (s != null) {
            adminContext.log(me.getId(), "DELETE_SPOT_INTRO", s.getCity() + s.getName());
        }
        return ApiResponse.ok(null);
    }
}
