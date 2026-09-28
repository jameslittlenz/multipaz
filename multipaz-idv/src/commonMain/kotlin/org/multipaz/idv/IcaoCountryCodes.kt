package org.multipaz.idv

/**
 * Maps ICAO 9303 (MRZ) three-letter country/nationality codes to the ISO 3166-1 alpha-2 codes
 * used elsewhere in the Photo ID doctype (`org.iso.23220.photoid.1`'s `issuing_country` and
 * `nationality` elements, per ISO/IEC 23220-4).
 *
 * The MRZ alpha-3 codes are mostly, but not exactly, ISO 3166-1 alpha-3: ICAO 9303 Part 3 also
 * defines codes with no ISO 3166-1 equivalent (e.g. `UNK` for unspecified nationality, `XXA`/`XXB`/
 * `XXC`/`XXX` for refugees and stateless persons, and legacy/administrative codes such as `D` for
 * Germany historically). This table only covers codes that map onto a real alpha-2 country code,
 * plus Validatopia's own alpha-3 code from the ISO 3166-1 user-assigned range.
 */
object IcaoCountryCodes {
    /** Validatopia's ISO 3166-1 user-assigned alpha-2 code. */
    const val VALIDATOPIA_ALPHA_2 = "XV"

    /** Validatopia's ISO 3166-1 user-assigned alpha-3 code. */
    const val VALIDATOPIA_ALPHA_3 = "XVA"

    private val alpha3ToAlpha2: Map<String, String> = buildMap {
        put(VALIDATOPIA_ALPHA_3, VALIDATOPIA_ALPHA_2)
        put("ABW", "AW"); put("AFG", "AF"); put("AGO", "AO"); put("AIA", "AI"); put("ALA", "AX")
        put("ALB", "AL"); put("AND", "AD"); put("ARE", "AE"); put("ARG", "AR"); put("ARM", "AM")
        put("ASM", "AS"); put("ATA", "AQ"); put("ATF", "TF"); put("ATG", "AG"); put("AUS", "AU")
        put("AUT", "AT"); put("AZE", "AZ"); put("BDI", "BI"); put("BEL", "BE"); put("BEN", "BJ")
        put("BES", "BQ"); put("BFA", "BF"); put("BGD", "BD"); put("BGR", "BG"); put("BHR", "BH")
        put("BHS", "BS"); put("BIH", "BA"); put("BLM", "BL"); put("BLR", "BY"); put("BLZ", "BZ")
        put("BMU", "BM"); put("BOL", "BO"); put("BRA", "BR"); put("BRB", "BB"); put("BRN", "BN")
        put("BTN", "BT"); put("BVT", "BV"); put("BWA", "BW"); put("CAF", "CF"); put("CAN", "CA")
        put("CCK", "CC"); put("CHE", "CH"); put("CHL", "CL"); put("CHN", "CN"); put("CIV", "CI")
        put("CMR", "CM"); put("COD", "CD"); put("COG", "CG"); put("COK", "CK"); put("COL", "CO")
        put("COM", "KM"); put("CPV", "CV"); put("CRI", "CR"); put("CUB", "CU"); put("CUW", "CW")
        put("CXR", "CX"); put("CYM", "KY"); put("CYP", "CY"); put("CZE", "CZ"); put("DEU", "DE")
        put("DJI", "DJ"); put("DMA", "DM"); put("DNK", "DK"); put("DOM", "DO"); put("DZA", "DZ")
        put("ECU", "EC"); put("EGY", "EG"); put("ERI", "ER"); put("ESH", "EH"); put("ESP", "ES")
        put("EST", "EE"); put("ETH", "ET"); put("FIN", "FI"); put("FJI", "FJ"); put("FLK", "FK")
        put("FRA", "FR"); put("FRO", "FO"); put("FSM", "FM"); put("GAB", "GA"); put("GBR", "GB")
        put("GEO", "GE"); put("GGY", "GG"); put("GHA", "GH"); put("GIB", "GI"); put("GIN", "GN")
        put("GLP", "GP"); put("GMB", "GM"); put("GNB", "GW"); put("GNQ", "GQ"); put("GRC", "GR")
        put("GRD", "GD"); put("GRL", "GL"); put("GTM", "GT"); put("GUF", "GF"); put("GUM", "GU")
        put("GUY", "GY"); put("HKG", "HK"); put("HMD", "HM"); put("HND", "HN"); put("HRV", "HR")
        put("HTI", "HT"); put("HUN", "HU"); put("IDN", "ID"); put("IMN", "IM"); put("IND", "IN")
        put("IOT", "IO"); put("IRL", "IE"); put("IRN", "IR"); put("IRQ", "IQ"); put("ISL", "IS")
        put("ISR", "IL"); put("ITA", "IT"); put("JAM", "JM"); put("JEY", "JE"); put("JOR", "JO")
        put("JPN", "JP"); put("KAZ", "KZ"); put("KEN", "KE"); put("KGZ", "KG"); put("KHM", "KH")
        put("KIR", "KI"); put("KNA", "KN"); put("KOR", "KR"); put("KWT", "KW"); put("LAO", "LA")
        put("LBN", "LB"); put("LBR", "LR"); put("LBY", "LY"); put("LCA", "LC"); put("LIE", "LI")
        put("LKA", "LK"); put("LSO", "LS"); put("LTU", "LT"); put("LUX", "LU"); put("LVA", "LV")
        put("MAC", "MO"); put("MAF", "MF"); put("MAR", "MA"); put("MCO", "MC"); put("MDA", "MD")
        put("MDG", "MG"); put("MDV", "MV"); put("MEX", "MX"); put("MHL", "MH"); put("MKD", "MK")
        put("MLI", "ML"); put("MLT", "MT"); put("MMR", "MM"); put("MNE", "ME"); put("MNG", "MN")
        put("MNP", "MP"); put("MOZ", "MZ"); put("MRT", "MR"); put("MSR", "MS"); put("MTQ", "MQ")
        put("MUS", "MU"); put("MWI", "MW"); put("MYS", "MY"); put("MYT", "YT"); put("NAM", "NA")
        put("NCL", "NC"); put("NER", "NE"); put("NFK", "NF"); put("NGA", "NG"); put("NIC", "NI")
        put("NIU", "NU"); put("NLD", "NL"); put("NOR", "NO"); put("NPL", "NP"); put("NRU", "NR")
        put("NZL", "NZ"); put("OMN", "OM"); put("PAK", "PK"); put("PAN", "PA"); put("PCN", "PN")
        put("PER", "PE"); put("PHL", "PH"); put("PLW", "PW"); put("PNG", "PG"); put("POL", "PL")
        put("PRI", "PR"); put("PRK", "KP"); put("PRT", "PT"); put("PRY", "PY"); put("PSE", "PS")
        put("PYF", "PF"); put("QAT", "QA"); put("REU", "RE"); put("ROU", "RO"); put("RUS", "RU")
        put("RWA", "RW"); put("SAU", "SA"); put("SDN", "SD"); put("SEN", "SN"); put("SGP", "SG")
        put("SGS", "GS"); put("SHN", "SH"); put("SJM", "SJ"); put("SLB", "SB"); put("SLE", "SL")
        put("SLV", "SV"); put("SMR", "SM"); put("SOM", "SO"); put("SPM", "PM"); put("SRB", "RS")
        put("SSD", "SS"); put("STP", "ST"); put("SUR", "SR"); put("SVK", "SK"); put("SVN", "SI")
        put("SWE", "SE"); put("SWZ", "SZ"); put("SXM", "SX"); put("SYC", "SC"); put("SYR", "SY")
        put("TCA", "TC"); put("TCD", "TD"); put("TGO", "TG"); put("THA", "TH"); put("TJK", "TJ")
        put("TKL", "TK"); put("TKM", "TM"); put("TLS", "TL"); put("TON", "TO"); put("TTO", "TT")
        put("TUN", "TN"); put("TUR", "TR"); put("TUV", "TV"); put("TWN", "TW"); put("TZA", "TZ")
        put("UGA", "UG"); put("UKR", "UA"); put("UMI", "UM"); put("URY", "UY"); put("USA", "US")
        put("UZB", "UZ"); put("VAT", "VA"); put("VCT", "VC"); put("VEN", "VE"); put("VGB", "VG")
        put("VIR", "VI"); put("VNM", "VN"); put("VUT", "VU"); put("WLF", "WF"); put("WSM", "WS")
        put("YEM", "YE"); put("ZAF", "ZA"); put("ZMB", "ZM"); put("ZWE", "ZW")
    }

    /**
     * Looks up the ISO 3166-1 alpha-2 code for an ICAO 9303 alpha-3 country/nationality code.
     *
     * @param alpha3 the three-letter code, as it appears in an MRZ (e.g. `"NZL"`).
     * @return the two-letter code (e.g. `"NZ"`), or `null` if [alpha3] isn't a recognized country
     *   code (this includes ICAO's non-country codes such as `UNK` for unspecified nationality).
     */
    fun toAlpha2(alpha3: String): String? = alpha3ToAlpha2[alpha3.uppercase()]
}
