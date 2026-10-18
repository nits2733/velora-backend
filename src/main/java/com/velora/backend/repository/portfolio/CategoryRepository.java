package com.velora.backend.repository.portfolio;

import com.velora.backend.entity.portfolio.Category;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    @EntityGraph(attributePaths = "subcategories")
    List<Category> findByParentIsNull();

    boolean existsByParentId(Long parentId);

    boolean existsByParentIsNullAndNameIgnoreCase(String name);

    boolean existsByParentIsNullAndNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByParentIdAndNameIgnoreCase(Long parentId, String name);

    boolean existsByParentIdAndNameIgnoreCaseAndIdNot(Long parentId, String name, Long id);
}
