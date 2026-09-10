package com.velora.backend.controller.quotation;

import com.velora.backend.dto.quotation.QuotationResponse;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.quotation.QuotationService;
import com.velora.backend.util.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quotations")
@RequiredArgsConstructor
@Tag(name = "Quotations", description = "Quotation listing across all of the current user's bookings")
public class QuotationsController {

    private static final int MAX_PAGE_SIZE = 50;

    private final QuotationService quotationService;

    @GetMapping
    @Operation(summary = "List quotations for the current user (customer sees own, professional sees assigned)")
    public ResponseEntity<PageResponse<QuotationResponse>> getMyQuotations(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        return ResponseEntity.ok(quotationService.getQuotationsForUser(principal.getId(), principal.getRole(), pageable));
    }
}
