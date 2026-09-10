package com.velora.backend.controller.portfolio;

import com.velora.backend.dto.portfolio.CategoryResponse;
import com.velora.backend.dto.portfolio.UpdateCategoryImageRequest;
import com.velora.backend.service.portfolio.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Design categories (public read; image managed by admin)")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @Operation(summary = "List all design categories")
    public ResponseEntity<List<CategoryResponse>> getAll() {
        return ResponseEntity.ok(categoryService.getAllCategories());
    }

    @PatchMapping("/{id}/image")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Set a category's image (admin only) - imageUrl is a Cloudinary URL "
            + "obtained beforehand via POST /api/media/upload")
    public ResponseEntity<CategoryResponse> updateImage(@PathVariable Long id,
                                                          @Valid @RequestBody UpdateCategoryImageRequest request) {
        return ResponseEntity.ok(categoryService.updateImage(id, request.imageUrl()));
    }
}
