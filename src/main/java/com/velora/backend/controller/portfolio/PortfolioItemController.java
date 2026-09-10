package com.velora.backend.controller.portfolio;

import com.velora.backend.dto.portfolio.PortfolioItemResponse;
import com.velora.backend.dto.portfolio.PortfolioItemSummaryResponse;
import com.velora.backend.dto.portfolio.SavePortfolioItemRequest;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.portfolio.PortfolioItemService;
import com.velora.backend.util.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
@Tag(name = "Portfolio", description = "Professional work-sample catalog: browse, search, filter (public)")
public class PortfolioItemController {

    private static final int MAX_PAGE_SIZE = 50;

    private final PortfolioItemService portfolioItemService;

    @GetMapping
    @Operation(summary = "Search/browse the portfolio catalog with pagination and filters")
    public ResponseEntity<PageResponse<PortfolioItemSummaryResponse>> search(
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String style,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field: createdAt, priceEstimate, title")
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(sortDirection, sanitizeSortField(sortBy)));

        return ResponseEntity.ok(portfolioItemService.search(category, search, style, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get portfolio item details by id")
    public ResponseEntity<PortfolioItemResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(portfolioItemService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('PROFESSIONAL')")
    @Operation(summary = "Upload a new portfolio item (professional only)")
    public ResponseEntity<PortfolioItemResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                          @Valid @RequestBody SavePortfolioItemRequest request) {
        PortfolioItemResponse response = portfolioItemService.create(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PROFESSIONAL')")
    @Operation(summary = "Replace one of your own portfolio items (professional only)")
    public ResponseEntity<PortfolioItemResponse> update(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable Long id,
                                                         @Valid @RequestBody SavePortfolioItemRequest request) {
        return ResponseEntity.ok(portfolioItemService.update(principal.getId(), id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PROFESSIONAL')")
    @Operation(summary = "Delete one of your own portfolio items (professional only, "
            + "blocked while a booking still references it)")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable Long id) {
        portfolioItemService.delete(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }

    private String sanitizeSortField(String sortBy) {
        return switch (sortBy) {
            case "priceEstimate" -> "interiorDesignDetails.priceEstimate";
            case "title" -> "title";
            default -> "createdAt";
        };
    }
}
