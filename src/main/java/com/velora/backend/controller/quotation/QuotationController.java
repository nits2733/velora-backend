package com.velora.backend.controller.quotation;

import com.velora.backend.dto.quotation.QuotationResponse;
import com.velora.backend.dto.quotation.SaveQuotationRequest;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.quotation.QuotationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings/{bookingId}/quotation")
@RequiredArgsConstructor
@Tag(name = "Quotations", description = "Post-consultation line-item estimate for a booking")
public class QuotationController {

    private final QuotationService quotationService;

    @PutMapping
    @PreAuthorize("hasRole('PROFESSIONAL')")
    @Operation(summary = "Create or update a draft quotation for a booking (professional only)")
    public ResponseEntity<QuotationResponse> saveDraft(@AuthenticationPrincipal UserPrincipal principal,
                                                        @PathVariable Long bookingId,
                                                        @Valid @RequestBody SaveQuotationRequest request) {
        return ResponseEntity.ok(quotationService.saveDraft(principal.getId(), bookingId, request));
    }

    @PostMapping("/send")
    @PreAuthorize("hasRole('PROFESSIONAL')")
    @Operation(summary = "Send a draft quotation to the customer (professional only)")
    public ResponseEntity<QuotationResponse> send(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long bookingId) {
        return ResponseEntity.ok(quotationService.send(principal.getId(), bookingId));
    }

    @GetMapping
    @Operation(summary = "Get the quotation for a booking (customer or assigned professional only)")
    public ResponseEntity<QuotationResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long bookingId) {
        return ResponseEntity.ok(quotationService.get(principal.getId(), bookingId));
    }

    @PatchMapping("/accept")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Accept a sent quotation (customer only)")
    public ResponseEntity<QuotationResponse> accept(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long bookingId) {
        return ResponseEntity.ok(quotationService.accept(principal.getId(), bookingId));
    }

    @PatchMapping("/reject")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Reject a sent quotation (customer only)")
    public ResponseEntity<QuotationResponse> reject(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long bookingId) {
        return ResponseEntity.ok(quotationService.reject(principal.getId(), bookingId));
    }
}
