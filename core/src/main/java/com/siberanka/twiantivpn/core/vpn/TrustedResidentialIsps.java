package com.siberanka.twiantivpn.core.vpn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in fixed-line, cable, fiber and mobile access networks that serve end users in
 * Turkey and the Turkic states.
 *
 * <p>Source: APNIC Labs per-AS end-user population estimates (60-day window ending
 * 2026-09-30), cross-checked against RIPE NCC registration country data. Only access networks
 * with at least 0.05% of the country's measured users are listed. Hosting/CDN providers,
 * satellite and eSIM/roaming operators, cross-border networks, academic and state networks
 * are intentionally excluded.</p>
 */
public final class TrustedResidentialIsps {
    private static final Map<String, List<String>> REGISTRY;

    static {
        Map<String, List<String>> registry = new LinkedHashMap<>();
        register(registry, "TR", // Turkey
                "AS9121", // Turk Telekomunikasyon Anonim Sirketi
                "AS34984", // Superonline Iletisim Hizmetleri A.S.
                "AS16135", // TURKCELL ILETISIM HIZMETLERI A.S.
                "AS20978", // TT Mobil Iletisim Hizmetleri A.S
                "AS15897", // Vodafone Telekomunikasyon A.S.
                "AS12735", // TurkNet Iletisim Hizmetleri A.S.
                "AS47524", // AS-TURKSAT
                "AS8386", // Vodafone Net Iletisim Hizmetler AS
                "AS15924", // Vodafone Net Iletisim Hizmetler AS
                "AS47331", // TTNet A.S.
                "AS34296", // Millenicom Telekomunikasyon Hizmetleri Anonim Sirketi
                "AS208972", // GIBIRNET ILETISIM HIZMETLERI SANAYI VE TICARET LIMITED SIRKETI
                "AS12978", // ANDROMEDA TV DIGITAL PLATFORM ISLETMECILIGI A.S.
                "AS202561", // High Speed Telekomunikasyon ve Hab. Hiz. Ltd. Sti.
                "AS206119", // Veganet Teknolojileri ve Hizmetleri LTD STI
                "AS44558", // Netonline Bilisim Sirketi LTD
                "AS204457", // Atlantis Telekomunikasyon Bilisim Hizmetleri San. ve Tic. Ltd. Sti.
                "AS201411", // GOKNET iletisim A.S.
                "AS206375", // NETSPEED INTERNET A.S.
                "AS213145", // FIBIM FIBERNET GSM SANAYI VE TICARET ANONIM SIRKETI
                "AS58293", // ALFA ILETISIM HIZMETLERI PAZARLAMA TICARET A.S.
                "AS42083", // Guneydogu Telekom int.bil. ve ilt. hiz. tic. ltd. sti.
                "AS209380", // Arat Telekominikasyon Tek. Bil.Hiz.San. ve Tic. Ltd.Sti
                "AS211496", // SURNET ILETISIM TEKNOLOJI TIC VE SAN LTD STI
                "AS205570", // SIBANET TELEKOM LIMITED SIRKETI
                "AS213261", // SADENET TELEKOMUNIKASYON HIZMETLERI ANONIM SIRKETI
                "AS47288", // FIXNET Telekomunikasyon Limited Sirketi
                "AS29399", // Ramtek Telekomunikasyon Hizmetleri Sanayi Ve Ticaret Limited Sirketi
                "AS211709" // TURBO NET TELEKOMUNIKASYON ELEKTRONIK HABERLESME BILISIM HIZMETLERI SANAYI TICARET LTD STI
        );
        register(registry, "AZ", // Azerbaijan
                "AS8814", // Aztelekom LLC
                "AS39232", // Uninet LLC
                "AS28787", // Aztelekom LLC
                "AS57293", // AG Telekom MMC.
                "AS15723", // AZERONLINE LTD JOINT ENTERPRISE
                "AS31721", // Azercell Telecom Ltd
                "AS42779", // Azerfon LLC
                "AS200446", // SELNET LLC
                "AS60258", // ENGINET LLC
                "AS197830", // BAKCELL LLC
                "AS201167", // Caspian Telecom LLC
                "AS203680", // Araz Tech LLC
                "AS203622", // GSP LLC
                "AS213398", // Fibernet LLC
                "AS199731", // The Educational Center for Internet and New Technologies of the Nakhchivan Autonomous Republic
                "AS49345", // BEEONLINE OJSC
                "AS13099", // AZ-EVRO TEL LLC
                "AS200154", // IZONE LLC
                "AS205547", // Flexnet LLC
                "AS50959", // BEEONLINE OJSC
                "AS211995", // A2Z Technologies CJSC
                "AS50274", // Alfanet LLC
                "AS205317", // MAXINET LLC
                "AS204986", // Sparktel LLC
                "AS209360", // Bulud Telecom LLC
                "AS196961", // Teleport LLC
                "AS48830", // TransEuroCom LLC
                "AS215148", // Delta Telecom Ltd
                "AS207251", // CASPEL LLC
                "AS39397", // Metronet LLC
                "AS215284", // Nest LLC
                "AS215328", // Fast Net Technology LLC
                "AS213402", // Rahat Telecom LLC
                "AS205367", // NetPoint LLC
                "AS213670", // TTNET LLC
                "AS48542", // START TELECOM Mahdud Masuliyyatli Camiyyati
                "AS200192", // Super Optik Tech LLC
                "AS203971", // AzFiberNet LLC
                "AS213139", // Faraon-1 LLC
                "AS216231", // Alnet LLC
                "AS214990", // GO LINE LLC
                "AS198828", // Abonnet LLC
                "AS200196", // Azerlink LLC
                "AS34170", // Aztelekom LLC
                "AS208410", // ARTKOM.NET LLC
                "AS29049", // Delta Telecom Ltd
                "AS215056", // NardaranInvest
                "AS44725", // Sinam LLC
                "AS34876" // SMART SISTEMZ TECHNOLOJI MMM
        );
        register(registry, "KZ", // Kazakhstan
                "AS9198", // JSC Kazakhtelecom
                "AS206026", // Kar-Tel LLC
                "AS29355", // Kcell JSC
                "AS48503", // Mobile Telecom-Service LLP
                "AS21299", // Kar-Tel LLC
                "AS29555", // Mobile Telecom-Service LLP
                "AS200590", // NLS Kazakhstan LLC
                "AS39824", // JSC Alma Telecommunications
                "AS41798", // JSC Transtelecom
                "AS35104", // QMOBILE JSC
                "AS41124", // BTcom Infocommunications Ltd.
                "AS56568", // X-COMMUNICATION LLP
                "AS58172", // Freedom Telecom Operations LLP
                "AS208946", // Sirius-2014 LLC
                "AS212999", // TOO Kainar-Media
                "AS8200", // Uplink LLC
                "AS197556", // Kar-Tel LLC
                "AS51878", // TOO IK-Broker
                "AS60757", // Optinet LLP
                "AS211028", // Spetsavtomatikaservice LLP
                "AS57013", // Eurasia-Star LLP
                "AS200218", // Tvoy Novy Telecom LLP
                "AS35566", // Kar-Tel LLC
                "AS208448", // TTK LLP
                "AS50482", // JSC Kazakhtelecom
                "AS41371", // BiKaDa TOO
                "AS59443", // Baynur and P Ltd.
                "AS57826", // Svyaz-INKOM-Servis i telekommunikatsii Ltd.
                "AS51997", // LLP Asket
                "AS208077", // COMPASTELECOM LLP
                "AS43994", // SMARTNET TOO
                "AS60286", // Agency-KA Ltd.
                "AS61367", // TOO B-TEL
                "AS58289", // Kazakhstan Network Communication LLP
                "AS215802", // i-LinkNet LLP
                "AS41007", // CTC ASTANA LTD
                "AS25548", // SCS - Telecom LLP
                "AS210273", // Montazh Stroj Servis TOO
                "AS201179", // Teraline Telecom LLP
                "AS212013", // TOO Telco Construction
                "AS205516", // NLS ASTANA LLP
                "AS43370", // OBIT-telecommunications, LLC
                "AS60411", // Network Kazakhstan LLC
                "AS202527", // Aktau Spec Montazh TOO
                "AS203886", // TOO Davion
                "AS214706" // KazTransNet LLP
        );
        register(registry, "UZ", // Uzbekistan
                "AS8193", // Uzbektelekom Joint Stock Company
                "AS49273", // COSCOM Liability Limited Company
                "AS201767", // Uzbektelekom Joint Stock Company
                "AS41202", // UNITEL LLC
                "AS64466", // UNIVERSAL MOBILE SYSTEMS LLC
                "AS59668", // Turon Media XK
                "AS43060", // IPLUS LLC
                "AS12365", // JC LLC Sarkor-Telecom
                "AS57016", // Inform-Service TV Ltd.
                "AS34718", // IST TELEKOM JV LLC
                "AS57764", // Flink Ltd.
                "AS43533", // OOO Gals Telecom
                "AS21001", // NETKA TELEKOM LLC
                "AS50025", // Net Television Ltd
                "AS207154", // PE Luseya Plyus
                "AS59706", // RUBICON WIRELESS COMMUNICATION JSC
                "AS209033", // CityNet LTD
                "AS39032", // IST TELEKOM JV LLC
                "AS58254", // Nano Telecom LLC
                "AS39568", // ASIA WIRELESS GROUP MChJ QK
                "AS212444", // LLC BROSS-TELECOM
                "AS215222", // Cable and mobile best service MCHJ
                "AS58330", // DP AlNet
                "AS197504" // GTELLUZ
        );
        register(registry, "KG", // Kyrgyzstan
                "AS47237", // NUR Telecom LLC
                "AS50223", // Alfa Telecom CJSC
                "AS41329", // Sky Mobile LLC
                "AS29061", // CJSC SAIMA TELECOM
                "AS50251", // NUR Telecom LLC
                "AS12764", // AKNET Ltd.
                "AS41750", // Mega-Line Ltd.
                "AS12997", // OJSC Kyrgyztelecom
                "AS207369", // Skynet Telecom, LLC
                "AS42837", // Extra Line LLC
                "AS42581", // Inform Communications Ltd.
                "AS8449", // ElCat Ltd.
                "AS207192", // Anfeya LLC
                "AS48271", // CJSC TELECOMMUNICATIONS COMPANY DUN
                "AS61010" // IPNET OOO
        );
        register(registry, "TM", // Turkmenistan
                "AS20661", // State Company of Electro Communications Turkmentelecom
                "AS51495", // Telephone Network of Ashgabat CJSC
                "AS59974", // Altyn Asyr CJSC
                "AS205471", // Telephone Network of Ashgabat City CJSC
                "AS204579" // Turkmen hemrasi CJSC
        );
        REGISTRY = Collections.unmodifiableMap(registry);
    }

    private TrustedResidentialIsps() {
    }

    public static Set<String> supportedCountries() {
        return REGISTRY.keySet();
    }

    public static List<String> asnsFor(String countryCode) {
        if (countryCode == null) {
            return Collections.emptyList();
        }
        List<String> asns = REGISTRY.get(countryCode.trim().toUpperCase(Locale.ROOT));
        return asns == null ? Collections.<String>emptyList() : asns;
    }

    public static List<String> asnsFor(Collection<String> countryCodes) {
        if (countryCodes == null || countryCodes.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> asns = new LinkedHashSet<>();
        for (String countryCode : countryCodes) {
            asns.addAll(asnsFor(countryCode));
        }
        return Collections.unmodifiableList(new ArrayList<>(asns));
    }

    public static List<String> allAsns() {
        return asnsFor(REGISTRY.keySet());
    }

    private static void register(Map<String, List<String>> registry, String countryCode, String... asns) {
        registry.put(countryCode, Collections.unmodifiableList(Arrays.asList(asns)));
    }
}
