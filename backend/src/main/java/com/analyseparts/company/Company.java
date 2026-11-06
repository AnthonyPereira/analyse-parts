package com.analyseparts.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Société cotée.
 * <ul>
 *   <li>ISIN : identifie le <em>titre</em> (l'action cotée), ex. FR0000120271 pour TotalEnergies.</li>
 *   <li>LEI : identifie l'<em>entité juridique</em>. C'est la clé qui relie la société à ses
 *       rapports annuels ESEF publiés sur filings.xbrl.org.</li>
 * </ul>
 */
@Entity
@Table(name = "company")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 12)
    private String isin;

    @Column(unique = true, length = 20)
    private String lei;

    @Column(nullable = false)
    private String name;

    private String legalName;

    @Column(length = 20)
    private String ticker;

    /** Code pays ISO 3166 sur 2 lettres. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 2)
    private String country;

    @Column(length = 100)
    private String sector;

    @Column(length = 100)
    private String market;

    private Instant leiCheckedAt;

    private Instant esefCheckedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Company() {
        // pour JPA
    }

    public Company(String isin, String name, String ticker, String country, String market) {
        this.isin = isin;
        this.name = name;
        this.ticker = ticker;
        this.country = country;
        this.market = market;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Met à jour les infos de cotation venant d'Euronext. */
    public void updateListing(String name, String ticker, String market) {
        this.name = name;
        this.ticker = ticker;
        this.market = market;
    }

    /** Enregistre le résultat d'une recherche GLEIF. */
    public void applyLei(String lei, String legalName, String country, Instant checkedAt) {
        this.lei = lei;
        this.legalName = legalName;
        if (country != null) {
            this.country = country;
        }
        this.leiCheckedAt = checkedAt;
    }

    public void markLeiChecked(Instant checkedAt) {
        this.leiCheckedAt = checkedAt;
    }

    /** L'index ESEF a été consulté avec succès (rapports importés, ou aucun rapport existant). */
    public void markEsefChecked(Instant checkedAt) {
        this.esefCheckedAt = checkedAt;
    }

    public Long getId() { return id; }
    public String getIsin() { return isin; }
    public String getLei() { return lei; }
    public String getName() { return name; }
    public String getLegalName() { return legalName; }
    public String getTicker() { return ticker; }
    public String getCountry() { return country; }
    public String getSector() { return sector; }
    public String getMarket() { return market; }
    public Instant getLeiCheckedAt() { return leiCheckedAt; }
    public Instant getEsefCheckedAt() { return esefCheckedAt; }
}
