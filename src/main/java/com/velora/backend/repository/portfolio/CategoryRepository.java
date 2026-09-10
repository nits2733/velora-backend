package com.velora.backend.repository.portfolio;

import com.velora.backend.entity.portfolio.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    boolean existsByNameIgnoreCase(String name);
}
