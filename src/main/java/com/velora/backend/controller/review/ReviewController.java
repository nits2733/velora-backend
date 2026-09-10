package com.velora.backend.controller.review;

import com.velora.backend.dto.review.CreateReviewRequest;
import com.velora.backend.dto.review.ReviewResponse;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.review.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings/{bookingId}/review")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Customer reviews of completed bookings")
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Leave a review for a completed booking (customer only)")
    public ResponseEntity<ReviewResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long bookingId,
                                                  @Valid @RequestBody CreateReviewRequest request) {
        ReviewResponse response = reviewService.createReview(principal.getId(), bookingId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
