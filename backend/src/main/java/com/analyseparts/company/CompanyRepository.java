package com.analyseparts.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByIsin(String isin);

    Optional<Company> findByLei(String lei);

    /** Sociétés jamais passées par GLEIF. */
    List<Company> findByLeiCheckedAtIsNullOrderByName();

    /** Sociétés avec un LEI dont l'index ESEF n'a jamais été consulté avec succès. */
    List<Company> findByLeiIsNotNullAndEsefCheckedAtIsNullOrderByName();

    List<Company> findByLeiIsNotNullOrderByName();

    /** Sociétés dont la fiche Euronext n'a jamais été lue, ou pas depuis {@code before}. */
    List<Company> findByFactsheetCheckedAtIsNullOrFactsheetCheckedAtBeforeOrderByName(java.time.Instant before);
}
