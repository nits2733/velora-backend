package com.velora.backend.repository.portfolio;

import com.velora.backend.entity.portfolio.PortfolioItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

public interface PortfolioItemRepository extends JpaRepository<PortfolioItem, Long>, JpaSpecificationExecutor<PortfolioItem> {
    long countByProfessionalIdAndCategoryIdIn(Long professionalId, Collection<Long> categoryIds);

    long countByProfessionalIdAndInteriorDesignDetailsStyleTagIgnoreCase(Long professionalId, String styleTag);

    boolean existsByCategoryId(Long categoryId);

    List<PortfolioItem> findByProfessionalId(Long professionalId);
}
