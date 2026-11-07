package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.skill.travel.trip.Trip;
import com.lifeassistant.skill.travel.trip.TripExpense;
import com.lifeassistant.skill.travel.trip.TripLedgerService;
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

/**
 * 行程账本接口（需要登录，只能动自己的行程）：改名称人数预算、预订记录（带凭证）、实际支出。
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}")
@RequiredArgsConstructor
public class TripLedgerController {

    private final AuthFacade authFacade;
    private final TripLedgerService ledger;

    /** PUT /api/v1/trips/{tripId}/info：改行程名称、人数、总预算。 */
    @PutMapping("/info")
    public ApiResponse<Trip> info(@PathVariable long tripId, @RequestBody TripLedgerService.InfoRequest request) {
        return ApiResponse.ok(ledger.updateInfo(authFacade.current(), tripId, request));
    }

    /** GET /api/v1/trips/{tripId}/bookings：行程的预订记录。 */
    @GetMapping("/bookings")
    public ApiResponse<List<TripLedgerService.BookingView>> bookings(@PathVariable long tripId) {
        return ApiResponse.ok(ledger.bookings(authFacade.current(), tripId));
    }

    /** POST /api/v1/trips/{tripId}/bookings：新增预订。 */
    @PostMapping("/bookings")
    public ApiResponse<TripLedgerService.BookingView> createBooking(@PathVariable long tripId,
                                                                    @RequestBody TripLedgerService.BookingRequest request) {
        return ApiResponse.ok(ledger.createBooking(authFacade.current(), tripId, request));
    }

    /** PUT /api/v1/trips/{tripId}/bookings/{id}：修改预订。 */
    @PutMapping("/bookings/{id}")
    public ApiResponse<TripLedgerService.BookingView> updateBooking(@PathVariable long tripId, @PathVariable long id,
                                                                    @RequestBody TripLedgerService.BookingRequest request) {
        return ApiResponse.ok(ledger.updateBooking(authFacade.current(), tripId, id, request));
    }

    /** DELETE /api/v1/trips/{tripId}/bookings/{id}：删除预订。 */
    @DeleteMapping("/bookings/{id}")
    public ApiResponse<Void> deleteBooking(@PathVariable long tripId, @PathVariable long id) {
        ledger.deleteBooking(authFacade.current(), tripId, id);
        return ApiResponse.ok();
    }

    /** GET /api/v1/trips/{tripId}/expenses：行程的支出。 */
    @GetMapping("/expenses")
    public ApiResponse<List<TripExpense>> expenses(@PathVariable long tripId) {
        return ApiResponse.ok(ledger.expenses(authFacade.current(), tripId));
    }

    /** POST /api/v1/trips/{tripId}/expenses：记一笔支出。 */
    @PostMapping("/expenses")
    public ApiResponse<TripExpense> createExpense(@PathVariable long tripId,
                                                  @RequestBody TripLedgerService.ExpenseRequest request) {
        return ApiResponse.ok(ledger.createExpense(authFacade.current(), tripId, request));
    }

    /** PUT /api/v1/trips/{tripId}/expenses/{id}：修改支出。 */
    @PutMapping("/expenses/{id}")
    public ApiResponse<TripExpense> updateExpense(@PathVariable long tripId, @PathVariable long id,
                                                  @RequestBody TripLedgerService.ExpenseRequest request) {
        return ApiResponse.ok(ledger.updateExpense(authFacade.current(), tripId, id, request));
    }

    /** DELETE /api/v1/trips/{tripId}/expenses/{id}：删除支出。 */
    @DeleteMapping("/expenses/{id}")
    public ApiResponse<Void> deleteExpense(@PathVariable long tripId, @PathVariable long id) {
        ledger.deleteExpense(authFacade.current(), tripId, id);
        return ApiResponse.ok();
    }
}
