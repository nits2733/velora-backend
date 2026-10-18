package com.velora.backend.service.portfolio;

import com.velora.backend.dto.portfolio.CategoryResponse;
import com.velora.backend.dto.portfolio.CreateCategoryRequest;
import com.velora.backend.dto.portfolio.UpdateCategoryRequest;
import com.velora.backend.entity.portfolio.Category;
import com.velora.backend.exception.DuplicateResourceException;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.mapper.portfolio.PortfolioItemMapper;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.portfolio.CategoryRepository;
import com.velora.backend.repository.portfolio.PortfolioItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final BookingRepository bookingRepository;
    private final PortfolioItemRepository portfolioItemRepository;
    private final PortfolioItemMapper portfolioItemMapper;

    @Cacheable("categories")
    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findByParentIsNull().stream()
                .map(portfolioItemMapper::toCategoryTreeResponse)
                .toList();
    }

    @CacheEvict(value = "categories", allEntries = true)
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        Category category = Category.builder()
                .name(request.name().trim())
                .description(request.description())
                .imageUrl(request.imageUrl())
                .build();
        if (request.parentId() == null) {
            if (request.serviceGroup() == null) {
                throw new IllegalArgumentException("serviceGroup is required for a top-level category");
            }
            if (categoryRepository.existsByParentIsNullAndNameIgnoreCase(category.getName())) {
                throw new DuplicateResourceException("Category already exists: " + category.getName());
            }
            category.setServiceGroup(request.serviceGroup());
        } else {
            Category parent = requireValidParent(request.parentId());
            if (categoryRepository.existsByParentIdAndNameIgnoreCase(parent.getId(), category.getName())) {
                throw new DuplicateResourceException("Subcategory already exists: " + category.getName());
            }
            category.setParent(parent);
            category.setServiceGroup(parent.getServiceGroup());
        }
        return portfolioItemMapper.toCategoryResponse(categoryRepository.save(category));
    }

    // portfolioSearch pages embed the category name, so a rename/re-parent must evict them too
    @CacheEvict(value = {"categories", "portfolioSearch"}, allEntries = true)
    @Transactional
    public CategoryResponse updateCategory(Long id, UpdateCategoryRequest request) {
        Category category = findOrThrow(id);
        String name = request.name().trim();

        if (request.parentId() == null) {
            if (request.serviceGroup() == null) {
                throw new IllegalArgumentException("serviceGroup is required for a top-level category");
            }
            if (categoryRepository.existsByParentIsNullAndNameIgnoreCaseAndIdNot(name, id)) {
                throw new DuplicateResourceException("Category already exists: " + name);
            }
            category.setParent(null);
            category.setServiceGroup(request.serviceGroup());
            if (category.getSubcategories() != null) {
                category.getSubcategories().forEach(child -> child.setServiceGroup(request.serviceGroup()));
            }
        } else {
            if (request.parentId().equals(id)) {
                throw new IllegalArgumentException("A category cannot be its own parent");
            }
            Category parent = requireValidParent(request.parentId());
            if (categoryRepository.existsByParentId(id)) {
                throw new IllegalArgumentException("A category that has subcategories cannot become a subcategory");
            }
            if (categoryRepository.existsByParentIdAndNameIgnoreCaseAndIdNot(parent.getId(), name, id)) {
                throw new DuplicateResourceException("Subcategory already exists: " + name);
            }
            category.setParent(parent);
            category.setServiceGroup(parent.getServiceGroup());
        }

        category.setName(name);
        category.setDescription(request.description());
        if (request.imageUrl() != null) {
            category.setImageUrl(request.imageUrl());
        }
        return portfolioItemMapper.toCategoryResponse(categoryRepository.save(category));
    }

    @CacheEvict(value = "categories", allEntries = true)
    @Transactional
    public void deleteCategory(Long id) {
        Category category = findOrThrow(id);
        if (categoryRepository.existsByParentId(id)) {
            throw new InvalidStateTransitionException("Category has subcategories and cannot be deleted");
        }
        if (bookingRepository.existsByCategoryId(id)) {
            throw new InvalidStateTransitionException("Category is referenced by bookings and cannot be deleted");
        }
        if (portfolioItemRepository.existsByCategoryId(id)) {
            throw new InvalidStateTransitionException("Category is referenced by portfolio items and cannot be deleted");
        }
        categoryRepository.delete(category);
    }

    @CacheEvict(value = "categories", allEntries = true)
    @Transactional
    public CategoryResponse updateImage(Long categoryId, String imageUrl) {
        Category category = findOrThrow(categoryId);
        category.setImageUrl(imageUrl);
        return portfolioItemMapper.toCategoryResponse(categoryRepository.save(category));
    }

    private Category findOrThrow(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));
    }

    private Category requireValidParent(Long parentId) {
        Category parent = findOrThrow(parentId);
        if (parent.getParent() != null) {
            throw new IllegalArgumentException("Categories support at most two levels; parent must be top-level");
        }
        return parent;
    }
}
