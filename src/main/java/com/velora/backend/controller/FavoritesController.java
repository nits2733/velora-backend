package com.velora.backend.controller;

import com.velora.backend.dto.portfolio.PortfolioItemSummaryResponse;
import com.velora.backend.dto.professional.ProfessionalSummaryResponse;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.FavoritesService;
import com.velora.backend.util.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Favorites", description = "Saved professionals and saved portfolio items (customer only)")
public class FavoritesController {

    private static final int MAX_PAGE_SIZE = 50;

    private final FavoritesService favoritesService;

    @PostMapping("/professionals/{professionalId}")
    @Operation(summary = "Save a professional as a favorite (idempotent)")
    public ResponseEntity<Void> saveProfessional(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long professionalId) {
        favoritesService.saveProfessional(principal.getId(), professionalId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/professionals/{professionalId}")
    @Operation(summary = "Remove a professional from favorites")
    public ResponseEntity<Void> unsaveProfessional(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long professionalId) {
        favoritesService.unsaveProfessional(principal.getId(), professionalId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/professionals")
    @Operation(summary = "List saved professionals")
    public ResponseEntity<PageResponse<ProfessionalSummaryResponse>> listProfessionals(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(favoritesService.listSavedProfessionals(principal.getId(), pageable(page, size)));
    }

    @PostMapping("/portfolio-items/{portfolioItemId}")
    @Operation(summary = "Save a portfolio item as a favorite (idempotent)")
    public ResponseEntity<Void> savePortfolioItem(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long portfolioItemId) {
        favoritesService.savePortfolioItem(principal.getId(), portfolioItemId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/portfolio-items/{portfolioItemId}")
    @Operation(summary = "Remove a portfolio item from favorites")
    public ResponseEntity<Void> unsavePortfolioItem(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long portfolioItemId) {
        favoritesService.unsavePortfolioItem(principal.getId(), portfolioItemId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/portfolio-items")
    @Operation(summary = "List saved portfolio items")
    public ResponseEntity<PageResponse<PortfolioItemSummaryResponse>> listPortfolioItems(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(favoritesService.listSavedPortfolioItems(principal.getId(), pageable(page, size)));
    }

    private Pageable pageable(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
