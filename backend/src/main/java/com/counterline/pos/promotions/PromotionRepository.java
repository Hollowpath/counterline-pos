package com.counterline.pos.promotions;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    List<Promotion> findByTypeAndActiveTrue(Promotion.Type type);
}
