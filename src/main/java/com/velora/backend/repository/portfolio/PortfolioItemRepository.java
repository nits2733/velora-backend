package com.velora.backend.repository.portfolio;

import com.velora.backend.entity.portfolio.PortfolioItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface PortfolioItemRepository extends JpaRepository<PortfolioItem, Long>, JpaSpecificationExecutor<PortfolioItem> {
    long countByProfessionalIdAndCategoryId(Long professionalId, Long categoryId);

    long countByProfessionalIdAndInteriorDesignDetailsStyleTagIgnoreCase(Long professionalId, String styleTag);

    List<PortfolioItem> findByProfessionalId(Long professionalId);
}
