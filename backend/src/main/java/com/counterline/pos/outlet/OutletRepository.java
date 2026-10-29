package com.counterline.pos.outlet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface OutletRepository extends JpaRepository<Outlet, Long> {

    List<Outlet> findByActiveTrueOrderByName();

    Optional<Outlet> findByIdAndActiveTrue(Long id);

    List<Outlet> findAllByOrderByCodeAsc();

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
