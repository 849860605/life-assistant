package com.lifeassistant.skill.travel.today;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.place.AmapPlaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 常用地址簿：家、公司等，增删改和设默认。默认地址同步成记忆里的"家的地址"，首页今日安排和星络对话都按它来；
 * 删掉默认地址时换下一个当默认，一个都没有就清掉记忆。以前只设过家庭地址的，第一次打开时自动建成一条"家"。
 */
@Service
@RequiredArgsConstructor
public class AddressBook {

    private static final int MAX = 20;

    private final UserAddressMapper mapper;
    private final TodayAgenda todayAgenda;
    private final AmapPlaceService places;

    /** 新增或修改的请求：label 是名字（家、公司…），isDefault 为 true 时设成默认。 */
    public record AddressRequest(String label, String address, Boolean isDefault) {
    }

    /** 我的常用地址，默认的在前。 */
    @Transactional
    public List<UserAddress> list(UserCtx ctx) {
        List<UserAddress> rows = rows(ctx);
        if (rows.isEmpty()) {
            String home = todayAgenda.homeAddress(ctx);
            if (home != null) {
                insert(ctx, "家", home, true);
                rows = rows(ctx);
            }
        }
        return rows;
    }

    /** 新增一个地址；第一个地址自动当默认。 */
    @Transactional
    public List<UserAddress> create(UserCtx ctx, AddressRequest r) {
        List<UserAddress> rows = list(ctx);
        if (rows.size() >= MAX) {
            throw bad("常用地址最多 " + MAX + " 个");
        }
        String label = label(r.label());
        String address = address(r.address());
        boolean makeDefault = rows.isEmpty() || Boolean.TRUE.equals(r.isDefault());
        UserAddress row = insert(ctx, label, address, false);
        if (makeDefault) {
            setDefault(ctx, row.getId());
        }
        return rows(ctx);
    }

    /** 修改一个地址；改的是默认地址时同步记忆。 */
    @Transactional
    public List<UserAddress> update(UserCtx ctx, long id, AddressRequest r) {
        UserAddress row = owned(ctx, id);
        String label = label(r.label());
        String address = address(r.address());
        mapper.update(new LambdaUpdateWrapper<UserAddress>().eq(UserAddress::getId, id)
                .set(UserAddress::getLabel, label).set(UserAddress::getAddress, address)
                .set(UserAddress::getUpdatedAt, OffsetDateTime.now()));
        if (Boolean.TRUE.equals(r.isDefault()) || Boolean.TRUE.equals(row.getIsDefault())) {
            setDefault(ctx, id);
        }
        return rows(ctx);
    }

    /** 删除一个地址；删的是默认的就把剩下最早的设成默认。 */
    @Transactional
    public List<UserAddress> delete(UserCtx ctx, long id) {
        UserAddress row = owned(ctx, id);
        mapper.deleteById(id);
        if (Boolean.TRUE.equals(row.getIsDefault())) {
            List<UserAddress> rest = rows(ctx);
            if (rest.isEmpty()) {
                todayAgenda.saveHome(ctx, null);
            } else {
                setDefault(ctx, rest.get(0).getId());
            }
        }
        return rows(ctx);
    }

    /** 设成默认地址，并同步到记忆里的家庭地址。 */
    @Transactional
    public List<UserAddress> makeDefault(UserCtx ctx, long id) {
        owned(ctx, id);
        setDefault(ctx, id);
        return rows(ctx);
    }

    private void setDefault(UserCtx ctx, long id) {
        mapper.update(new LambdaUpdateWrapper<UserAddress>().eq(UserAddress::getUserId, ctx.userId())
                .set(UserAddress::getIsDefault, false));
        mapper.update(new LambdaUpdateWrapper<UserAddress>().eq(UserAddress::getId, id)
                .set(UserAddress::getIsDefault, true));
        todayAgenda.saveHome(ctx, mapper.selectById(id).getAddress());
    }

    private UserAddress insert(UserCtx ctx, String label, String address, boolean isDefault) {
        UserAddress row = new UserAddress();
        row.setUserId(ctx.userId());
        row.setLabel(label);
        row.setAddress(address);
        row.setIsDefault(isDefault);
        mapper.insert(row);
        return row;
    }

    private List<UserAddress> rows(UserCtx ctx) {
        return mapper.selectList(new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, ctx.userId())
                .orderByDesc(UserAddress::getIsDefault).orderByAsc(UserAddress::getId));
    }

    private UserAddress owned(UserCtx ctx, long id) {
        UserAddress row = mapper.selectById(id);
        if (row == null || !row.getUserId().equals(ctx.userId())) {
            throw BizException.notFound("地址");
        }
        return row;
    }

    private static String label(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > 20) {
            throw bad("名称要 1 到 20 个字，比如：家、公司");
        }
        return clean;
    }

    /** 地址要在高德上找得到，免得算不出路程。 */
    private String address(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            throw bad("请填写地址");
        }
        if (clean.length() > 120) {
            throw bad("地址最多 120 个字");
        }
        if (places.center(clean).isEmpty()) {
            throw bad("高德上没找到这个地址，写得具体一点，比如带上城市和小区名");
        }
        return clean;
    }

    private static BizException bad(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }
}
