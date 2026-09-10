package com.velora.backend.service.portfolio;

import com.velora.backend.dto.portfolio.CategoryResponse;
import com.velora.backend.entity.portfolio.Category;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.mapper.portfolio.PortfolioItemMapper;
import com.velora.backend.repository.portfolio.CategoryRepository;
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
    private final PortfolioItemMapper portfolioItemMapper;

    @Cacheable("categories")
    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        return categoryRepository.findAll().stream()
                .map(portfolioItemMapper::toCategoryResponse)
                .toList();
    }

    @CacheEvict("categories")
    @Transactional
    public CategoryResponse updateImage(Long categoryId, String imageUrl) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
        category.setImageUrl(imageUrl);
        return portfolioItemMapper.toCategoryResponse(categoryRepository.save(category));
    }
}
