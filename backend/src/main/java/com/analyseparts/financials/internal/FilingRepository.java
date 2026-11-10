package com.analyseparts.financials.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FilingRepository extends JpaRepository<FilingEntity, Long> {

    boolean existsBySourceId(String sourceId);

    boolean existsByCompanyId(Long companyId);

    Optional<FilingEntity> findFirstByCompanyIdOrderByPeriodEndDesc(Long companyId);

    /** Date de clôture du rapport le plus récent, par société. */
    interface LatestPeriodEnd {
        Long getCompanyId();

        LocalDate getLatest();
    }

    @Query("select f.periodEnd from FilingEntity f where f.companyId = :companyId")
    List<LocalDate> findPeriodEnds(Long companyId);

    /** Le rapport le plus récent de chaque société (une ligne par société, une seule requête). */
    @Query("select f from FilingEntity f where f.periodEnd = "
            + "(select max(g.periodEnd) from FilingEntity g where g.companyId = f.companyId)")
    List<FilingEntity> findLatestFilings();

    @Query("select f.companyId as companyId, max(f.periodEnd) as latest from FilingEntity f group by f.companyId")
    List<LatestPeriodEnd> findLatestPeriodEnds();
}
