package com.analyseparts.company;

/**
 * Classification sectorielle ICB (Industry Classification Benchmark, gérée par FTSE Russell,
 * utilisée par Euronext). Quatre niveaux emboîtés, du plus large au plus fin :
 * <pre>
 * Industrie     40       Consumer Discretionary
 * Super-secteur 4020     Consumer Products and Services
 * Secteur       402040   Personal Goods
 * Sous-secteur  40204020 Clothing and Accessories      (LVMH)
 * </pre>
 *
 * @param code code à 8 chiffres du sous-secteur ; ses premiers chiffres donnent les niveaux supérieurs
 */
public record Icb(String code, String industry, String supersector, String sector, String subsector) {

    public String industryCode() {
        return code.substring(0, 2);
    }

    public String supersectorCode() {
        return code.substring(0, 4);
    }

    public String sectorCode() {
        return code.substring(0, 6);
    }
}
