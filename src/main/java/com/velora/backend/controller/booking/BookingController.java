package com.velora.backend.controller.booking;

import com.velora.backend.dto.booking.AddInspirationImageRequest;
import com.velora.backend.dto.booking.AssignProfessionalRequest;
import com.velora.backend.dto.booking.BookingRequest;
import com.velora.backend.dto.booking.BookingResponse;
import com.velora.backend.dto.booking.BookingStatusUpdateRequest;
import com.velora.backend.dto.booking.BookingTimelineEventResponse;
import com.velora.backend.dto.booking.ProfessionalMatchResponse;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.booking.BookingService;
import com.velora.backend.service.professional.ProfessionalMatchingService;
import com.velora.backend.util.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Full Home Services and Individual Service booking lifecycle")
public class BookingController {

    private static final int MAX_PAGE_SIZE = 50;

    private final BookingService bookingService;
    private final ProfessionalMatchingService professionalMatchingService;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Create a booking - Full Home Services or Individual Service; always starts "
            + "awaiting admin assignment, the customer never names a professional directly")
    public ResponseEntity<BookingResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody BookingRequest request) {
        BookingResponse response = bookingService.createBooking(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List bookings for the current user (customer sees own, professional sees assigned)")
    public ResponseEntity<PageResponse<BookingResponse>> getBookings(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
            Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "scheduledAt"));

        return ResponseEntity.ok(bookingService.getBookingsForUser(principal.getId(), principal.getRole(), pageable));
    }

    @GetMapping("/awaiting-assignment")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List bookings awaiting professional assignment, oldest first (admin only)")
    public ResponseEntity<PageResponse<BookingResponse>> awaitingAssignment(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.ASC, "createdAt"));

        return ResponseEntity.ok(bookingService.getBookingsAwaitingAssignment(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single booking's details")
    public ResponseEntity<BookingResponse> getById(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long id) {
        return ResponseEntity.ok(bookingService.getById(principal.getId(), principal.getRole(), id));
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Get the status-timeline audit trail for a booking (participant or admin only)")
    public ResponseEntity<List<BookingTimelineEventResponse>> timeline(@AuthenticationPrincipal UserPrincipal principal,
                                                                        @PathVariable Long id) {
        return ResponseEntity.ok(bookingService.getTimeline(principal.getId(), principal.getRole(), id));
    }

    @PostMapping("/{id}/inspiration-images")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Attach an inspiration/moodboard image to a booking (owning customer only)")
    public ResponseEntity<BookingResponse> addInspirationImage(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable Long id,
                                                                 @Valid @RequestBody AddInspirationImageRequest request) {
        return ResponseEntity.ok(bookingService.addInspirationImage(principal.getId(), id, request.imageUrl()));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Cancel a booking (customer only, before completion)")
    public ResponseEntity<BookingResponse> cancel(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long id) {
        return ResponseEntity.ok(bookingService.cancel(principal.getId(), id));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('PROFESSIONAL')")
    @Operation(summary = "Update booking status (assigned professional only)")
    public ResponseEntity<BookingResponse> updateStatus(@AuthenticationPrincipal UserPrincipal principal,
                                                          @PathVariable Long id,
                                                          @Valid @RequestBody BookingStatusUpdateRequest request) {
        return ResponseEntity.ok(bookingService.updateStatus(principal.getId(), id, request.status()));
    }

    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Assign a professional to a booking awaiting assignment (admin only)")
    public ResponseEntity<BookingResponse> assign(@PathVariable Long id,
                                                   @Valid @RequestBody AssignProfessionalRequest request) {
        return ResponseEntity.ok(bookingService.assignProfessional(id, request.professionalId()));
    }

    @GetMapping("/{id}/recommendations")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rank candidate professionals for a booking awaiting assignment (admin only)")
    public ResponseEntity<List<ProfessionalMatchResponse>> recommendations(@PathVariable Long id) {
        return ResponseEntity.ok(professionalMatchingService.recommend(id));
    }
}
