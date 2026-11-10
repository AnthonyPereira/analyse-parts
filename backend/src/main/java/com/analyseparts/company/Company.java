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
import java.util.Optional;

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
    private String market;

    private Instant leiCheckedAt;

    private Instant esefCheckedAt;

    /** Code MIC du marché de cotation (XPAR = Euronext Paris). */
    @Column(nullable = false, length = 4)
    private String mic = "XPAR";

    /** Classification sectorielle ICB (code du sous-secteur et libellés des 4 niveaux). */
    @Column(length = 8)
    private String icbCode;
    private String icbIndustry;
    private String icbSupersector;
    private String icbSector;
    private String icbSubsector;

    /** Nombre d'actions admises à la cotation (fiche Euronext). */
    private Long sharesAdmitted;

    private Instant factsheetCheckedAt;

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
    void initializeTimestamps() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void refreshUpdatedAt() {
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
    /** Enregistre la fiche Euronext (secteur et nombre d'actions ; null si absent de la fiche). */
    public void applyFactsheet(Icb icb, Long sharesAdmitted, Instant checkedAt) {
        if (icb != null) {
            this.icbCode = icb.code();
            this.icbIndustry = icb.industry();
            this.icbSupersector = icb.supersector();
            this.icbSector = icb.sector();
            this.icbSubsector = icb.subsector();
        }
        if (sharesAdmitted != null) {
            this.sharesAdmitted = sharesAdmitted;
        }
        this.factsheetCheckedAt = checkedAt;
    }

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
    public String getMic() { return mic; }
    public Long getSharesAdmitted() { return sharesAdmitted; }
    public Instant getFactsheetCheckedAt() { return factsheetCheckedAt; }

    /** Classification ICB, si la fiche Euronext a été lue. */
    /**
     * Caisse régionale de Crédit Agricole : la bourse ne cote que ses certificats coopératifs
     * d'investissement (CCI), une petite partie de son capital ; le reste appartient aux
     * sociétaires et à Crédit Agricole SA. Cours × nombre de CCI ne vaut donc pas la caisse
     * entière : on ne peut pas le comparer à son bénéfice ou à ses capitaux propres.
     * Euronext les nomme « CRCAM … » ou « … CCI ».
     */
    public boolean isCooperativeCertificate() {
        return name != null && (name.startsWith("CRCAM ") || name.endsWith(" CCI") || name.endsWith("CCI"));
    }

    public Optional<Icb> getIcb() {
        return icbCode == null ? Optional.empty()
                : Optional.of(new Icb(icbCode, icbIndustry, icbSupersector, icbSector, icbSubsector));
    }
    public String getMarket() { return market; }
    public Instant getLeiCheckedAt() { return leiCheckedAt; }
    public Instant getEsefCheckedAt() { return esefCheckedAt; }
}
