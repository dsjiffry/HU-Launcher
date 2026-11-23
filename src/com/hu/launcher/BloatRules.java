package com.hu.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * On-device first guess. Deliberately conservative: anything a head unit might need (CAN bus,
 * radio, Bluetooth, cameras, MCU, vendor core) is KEEP. The PC verdicts override these.
 */
final class BloatRules {
    static final int BLOAT = 0, MAYBE = 1, KEEP = 2;

    static final class Verdict {
        final int level;
        final String why;
        final String source; // "rules", "pc", "you"
        Verdict(int level, String why, String source) { this.level = level; this.why = why; this.source = source; }
    }

    private static final Map<String, String> BLOAT_EXACT = new HashMap<>();
    private static final Map<String, String> MAYBE_EXACT = new HashMap<>();
    private static final Map<String, String> KEEP_EXACT = new HashMap<>();

    private static void b(String why, String... pkgs) { for (String p : pkgs) BLOAT_EXACT.put(p, why); }
    private static void m(String why, String... pkgs) { for (String p : pkgs) MAYBE_EXACT.put(p, why); }
    private static void k(String why, String... pkgs) { for (String p : pkgs) KEEP_EXACT.put(p, why); }

    static {
        b("MediaTek debug logger - runs in the background", "com.mediatek.mtklogger");
        b("GPS test tool", "com.mediatek.ygps");
        b("Location engineering tool", "com.mediatek.lbs.em2.ui");
        b("Phone-to-phone data transfer", "com.mediatek.datatransfer");
        b("Call recorder (no phone calls here)", "com.mediatek.callrecorder");
        b("Old stock e-mail app", "com.android.email", "com.android.exchange");
        b("Old stock browser - outdated and insecure", "com.android.browser");
        b("Search bar widget", "com.android.quicksearchbox");
        b("Screensavers", "com.android.dreams.basic", "com.android.dreams.phototable");
        b("Live wallpapers", "com.android.wallpaper.livepicker", "com.android.galaxy4", "com.android.noisefield",
                "com.android.phasebeam", "com.android.magicsmoke", "com.android.musicvis",
                "com.android.wallpaper.holospiral", "com.android.wallpaper");
        b("Printing", "com.android.printspooler", "com.android.bips");
        b("Android version easter egg", "com.android.egg");
        b("System tracing tool", "com.android.traceur");
        b("Partner bookmarks", "com.android.providers.partnerbookmarks", "com.android.bookmarkprovider");
        b("Call-log backup", "com.android.calllogbackup");
        b("Sound recorder", "com.android.soundrecorder");
        b("Google Play Music - service shut down", "com.google.android.music");
        b("Google Play Movies", "com.google.android.videos");
        b("Google Play Books", "com.google.android.apps.books");
        b("Hangouts / Duo", "com.google.android.talk", "com.google.android.apps.tachyon");
        b("Google Drive", "com.google.android.apps.docs");

        m("Clock app - this launcher already shows the time", "com.android.deskclock");
        m("Old music player - keep if you play music from USB", "com.android.music");
        m("Gallery - keep if you view photos/videos from USB", "com.android.gallery3d");
        m("Calendar", "com.android.calendar", "com.android.providers.calendar");
        m("Calculator", "com.android.calculator2");
        m("SMS app - keep if the unit has a SIM", "com.android.mms", "com.android.messaging", "com.android.smspush");
        m("SIM toolkit - only needed with a SIM card", "com.android.stk");
        m("Emergency alerts - only with a SIM card", "com.android.cellbroadcastreceiver");
        m("Wallpaper cropper", "com.android.wallpapercropper");
        m("Sound effects / equaliser", "com.android.musicfx");
        m("MediaTek DuraSpeed background killer", "com.mediatek.duraspeed");
        m("Scheduled power on/off", "com.mediatek.schpwronoff");
        m("Engineer mode (hidden test menu)", "com.mediatek.engineermode");
        m("YouTube", "com.google.android.youtube");
        m("Google Photos", "com.google.android.apps.photos");
        m("Gmail", "com.google.android.gm");
        m("Chrome", "com.android.chrome");
        m("Google app / voice search", "com.google.android.googlequicksearchbox");

        k("Your music", MediaListener.SPOTIFY);
        k("Navigation", "com.google.android.apps.maps", "com.waze");
        k("Core Android", "android", "com.android.systemui", "com.android.settings", "com.android.phone",
                "com.android.bluetooth", "com.android.shell", "com.android.inputdevices", "com.android.keychain",
                "com.android.location.fused", "com.android.server.telecom", "com.android.packageinstaller",
                "com.google.android.packageinstaller", "com.android.permissioncontroller",
                "com.android.externalstorage", "com.android.mtp", "com.android.documentsui",
                "com.android.defcontainer", "com.android.proxyhandler", "com.android.vpndialogs",
                "com.android.certinstaller", "com.android.carrierconfig", "com.android.webview",
                "com.google.android.webview", "com.android.ext.services", "com.google.android.ext.services",
                "com.android.networkstack", "com.android.captiveportallogin", "com.android.statementservice",
                "com.android.se", "com.android.nfc", "com.android.storagemanager", "com.android.backupconfirm",
                "com.android.sharedstoragebackup", "com.android.settings.intelligence", "com.android.wallpaperbackup",
                "com.android.onetimeinitializer", "com.android.managedprovisioning", "com.android.htmlviewer",
                "com.android.dynsystem", "com.android.emergency", "com.android.contacts", "com.android.dialer",
                "com.mediatek.batterywarning", "com.mediatek.ims", "com.mediatek.atci.service");
        k("Google Play services / store", "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
                "com.google.android.gsf.login", "com.google.android.syncadapters.contacts");
    }

    /** Chinese-market apps that are useless outside China. */
    private static final String[] CN_BLOAT = {"com.baidu.", "com.kugou.", "com.kuwo.", "cn.kuwo.", "com.netease.",
            "com.ximalaya.", "com.qiyi.", "com.youku.", "com.sohu.", "com.autonavi.", "com.amap.", "com.ss.android.",
            "com.ucmobile", "com.uc.", "com.sogou.", "com.dianping.", "com.sina.", "com.taobao.", "com.alibaba.",
            "com.tencent.", "com.qq.", "com.iqiyi."};

    /** Anything that smells like car hardware or head-unit vendor core stays. */
    private static final String[] HW_KEEP = {"canbus", "can.", ".can", "mcu", "carplay", "androidauto", "zlink",
            "autokit", "carlink", "carlife", "easyconn", "radio", "fmradio", ".fm", "tuner", "bluetooth", ".bt", "btphone",
            "camera", "avm", "backcar", "reverse", "steer", "swc", "keyservice", "factory", "carsetting",
            "inputmethod", "keyboard", "ime", "ota", "update", "gps", "location", "provider", "telephony", "audio",
            "dvr", "tpms", "obd", "amp", "eq", "com.syu.", "com.microntek.", "com.ts.", "com.fyt.", "com.hzbhd.",
            "com.android.server.", "com.mediatek.location"};

    static Verdict classify(PkgScan.Entry e, String self) {
        String p = e.pkg;
        String lower = p.toLowerCase();
        if (p.equals(self)) return keep("This launcher");
        String why = KEEP_EXACT.get(p);
        if (why != null) return keep(why);
        why = BLOAT_EXACT.get(p);
        if (why != null) return new Verdict(BLOAT, why, "rules");
        if (e.isHome) return keep("Backup home screen - keep in case this one fails");
        why = MAYBE_EXACT.get(p);
        if (why != null) return new Verdict(MAYBE, why, "rules");
        for (String s : HW_KEEP) if (lower.contains(s)) return keep("Looks like car hardware / system service");
        for (String s : CN_BLOAT) {
            if (lower.startsWith(s)) {
                return new Verdict(e.system ? BLOAT : MAYBE, "Chinese-market app", "rules");
            }
        }
        if (!e.system) return keep("You installed this");
        if (!e.launchable) return keep("Background system component");
        return new Verdict(MAYBE, "Preinstalled app - your call", "rules");
    }

    private static Verdict keep(String why) { return new Verdict(KEEP, why, "rules"); }

    static String name(int level) { return level == BLOAT ? "bloat" : level == MAYBE ? "maybe" : "keep"; }

    static int parse(String s) {
        if ("bloat".equalsIgnoreCase(s)) return BLOAT;
        if ("maybe".equalsIgnoreCase(s)) return MAYBE;
        return KEEP;
    }

    private BloatRules() { }
}
