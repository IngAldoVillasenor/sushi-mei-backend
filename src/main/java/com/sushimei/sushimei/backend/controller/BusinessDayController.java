package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.businessday.BusinessDayResponse;
import com.sushimei.sushimei.backend.businessday.BusinessDayService;
import com.sushimei.sushimei.backend.businessday.CashExpenseCreateResponse;
import com.sushimei.sushimei.backend.businessday.CashExpenseRequest;
import com.sushimei.sushimei.backend.businessday.CashExpenseResponse;
import com.sushimei.sushimei.backend.businessday.CashExpenseResult;
import com.sushimei.sushimei.backend.businessday.CashExpenseService;
import com.sushimei.sushimei.backend.businessday.CloseBusinessDayRequest;
import com.sushimei.sushimei.backend.businessday.OpenBusinessDayRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/business-days")
public class BusinessDayController {

    private final BusinessDayService businessDayService;
    private final CashExpenseService cashExpenseService;

    public BusinessDayController(BusinessDayService businessDayService, CashExpenseService cashExpenseService) {
        this.businessDayService = Objects.requireNonNull(businessDayService, "businessDayService must not be null");
        this.cashExpenseService = Objects.requireNonNull(cashExpenseService, "cashExpenseService must not be null");
    }

    @PostMapping("/open")
    public ResponseEntity<BusinessDayResponse> open(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody OpenBusinessDayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(businessDayService.open(userId(jwt), request));
    }

    @GetMapping("/current")
    public ResponseEntity<BusinessDayResponse> current() {
        return businessDayService.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/current/close")
    public BusinessDayResponse close(@AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody CloseBusinessDayRequest request) {
        return businessDayService.close(userId(jwt), request);
    }

    @PostMapping("/current/reopen")
    public BusinessDayResponse reopen(@AuthenticationPrincipal Jwt jwt) {
        return businessDayService.reopen(userId(jwt));
    }

    @PostMapping("/current/cash-expenses")
    public ResponseEntity<CashExpenseCreateResponse> createCashExpense(@AuthenticationPrincipal Jwt jwt,
                                                                         @Valid @RequestBody CashExpenseRequest request) {
        CashExpenseCreateResponse response = cashExpenseService.create(userId(jwt), request);
        return response.result() == CashExpenseResult.ALREADY_CREATED
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/current/cash-expenses")
    public List<CashExpenseResponse> currentCashExpenses() {
        return cashExpenseService.listCurrent();
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
