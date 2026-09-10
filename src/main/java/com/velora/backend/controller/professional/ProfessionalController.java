package com.velora.backend.controller.professional;

import com.velora.backend.dto.professional.ProfessionalPublicProfileResponse;
import com.velora.backend.dto.professional.ProfessionalSummaryResponse;
import com.velora.backend.dto.review.PublicReviewResponse;
import com.velora.backend.entity.professional.AvailabilityStatus;
import com.velora.backend.service.professional.ProfessionalService;
import com.velora.backend.service.review.ReviewService;
import com.velora.backend.util.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/professionals")
@RequiredArgsConstructor
@Tag(name = "Professionals", description = "Public professional directory and profiles (public)")
public class ProfessionalController {

    private static final int MAX_PAGE_SIZE = 50;

    private final ProfessionalService professionalService;
    private final ReviewService reviewService;

    @GetMapping
    @Operation(summary = "Search/browse the professional directory with pagination and filters")
    public ResponseEntity<PageResponse<ProfessionalSummaryResponse>> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String specialization,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) AvailabilityStatus availability,
            @RequestParam(required = false) Integer minExperience,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field: averageRating, yearsExperience, fullName, createdAt")
            @RequestParam(defaultValue = "averageRating") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(sortDirection, sanitizeSortField(sortBy)));

        return ResponseEntity.ok(professionalService.search(
                search, specialization, city, availability, minExperience, minRating, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a professional's public profile")
    public ResponseEntity<ProfessionalPublicProfileResponse> getPublicProfile(@PathVariable Long id) {
        return ResponseEntity.ok(professionalService.getPublicProfile(id));
    }

    @GetMapping("/{id}/reviews")
    @Operation(summary = "List a professional's reviews, newest first")
    public ResponseEntity<PageResponse<PublicReviewResponse>> getReviews(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        return ResponseEntity.ok(reviewService.getProfessionalReviews(id, pageable));
    }

    private String sanitizeSortField(String sortBy) {
        return switch (sortBy) {
            case "yearsExperience", "createdAt" -> sortBy;
            case "fullName" -> "user.fullName";
            default -> "averageRating";
        };
    }
}
