package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.skill.travel.today.AddressBook;
import com.lifeassistant.skill.travel.today.UserAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 常用地址（需要登录）：列表、新增、修改、删除、设默认。每个写操作都返回最新的整份列表。 */
@RestController
@RequestMapping("/api/v1/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AuthFacade authFacade;
    private final AddressBook addressBook;

    /** GET /api/v1/addresses：我的常用地址，默认的在前。 */
    @GetMapping
    public ApiResponse<List<UserAddress>> list() {
        return ApiResponse.ok(addressBook.list(authFacade.current()));
    }

    /** POST /api/v1/addresses：新增地址（高德找得到才存）。 */
    @PostMapping
    public ApiResponse<List<UserAddress>> create(@RequestBody AddressBook.AddressRequest request) {
        return ApiResponse.ok(addressBook.create(authFacade.current(), request));
    }

    /** PUT /api/v1/addresses/{id}：修改地址。 */
    @PutMapping("/{id}")
    public ApiResponse<List<UserAddress>> update(@PathVariable long id, @RequestBody AddressBook.AddressRequest request) {
        return ApiResponse.ok(addressBook.update(authFacade.current(), id, request));
    }

    /** DELETE /api/v1/addresses/{id}：删除地址。 */
    @DeleteMapping("/{id}")
    public ApiResponse<List<UserAddress>> delete(@PathVariable long id) {
        return ApiResponse.ok(addressBook.delete(authFacade.current(), id));
    }

    /** POST /api/v1/addresses/{id}/default：设成出发默认地址。 */
    @PostMapping("/{id}/default")
    public ApiResponse<List<UserAddress>> makeDefault(@PathVariable long id) {
        return ApiResponse.ok(addressBook.makeDefault(authFacade.current(), id));
    }
}
