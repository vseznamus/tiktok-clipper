package ru.tiktokclipper.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Подпись и хэштеги для TikTok из имени файла: «Breaking.Bad.S01E05.1080p.mkv» → «Breaking Bad · 1 сезон, 5 серия #breakingbad …». */
public final class Captions {
    private Captions() {
    }

    static final Pattern JUNK = Pattern.compile("(?i)\\b(\\d{3,4}p|web-?dl|web-?rip|bdrip|bluray|hdrip|dvdrip|hdtv|x26[45]|h\\.?26[45]|hevc|avc|"
            + "aac|ac3|dts|rus|eng|sub|subs|lostfilm|newstudio|novafilm|kurazh|amedia|rip|hdr|sdr|10bit|proper|repack)\\b");

    static final Pattern DATE = Pattern.compile("(20\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})");

    /** Подпись для клипа со стрима: «Стример Вася · эфир 28.10» + #стримервася #логин #твич #стрим … */
    public static String stream(String streamer, String login, String fileName) {
        String name = streamer == null || streamer.trim().isEmpty() ? "Стрим" : streamer.trim();
        StringBuilder sb = new StringBuilder(name);
        Matcher d = DATE.matcher(fileName == null ? "" : fileName);
        if (d.find()) sb.append(" · эфир ").append(d.group(3)).append('.').append(d.group(2));
        Set<String> tags = new LinkedHashSet<>();
        String t1 = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_]+", "");
        if (t1.length() >= 2 && t1.length() <= 30) tags.add("#" + t1);
        String t2 = login == null ? "" : login.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "");
        if (t2.length() >= 2) tags.add("#" + t2);
        tags.add("#твич");
        tags.add("#стрим");
        tags.add("#нарезки");
        tags.add("#моменты");
        sb.append("\n").append(String.join(" ", tags));
        return sb.toString();
    }

    public static String make(String fileName) {
        String n = fileName == null ? "" : fileName;
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        int[] se = episodeOf(n);
        String title = n;
        Matcher m = Pattern.compile("(?i)([\\s._-]*(s\\d{1,2}[\\s._-]*e\\d{1,3}|\\d{1,2}x\\d{2,3}|\\d{1,2}\\s*сезон|сезон\\s*\\d|серия\\s*\\d|\\d{1,3}\\s*серия|\\(?(19|20)\\d{2}\\)?))").matcher(title);
        if (m.find()) title = title.substring(0, m.start());
        title = JUNK.matcher(title.replaceAll("[._]+", " ")).replaceAll(" ").replaceAll("[\\[\\](){}]+", " ").replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) title = "Момент";
        StringBuilder sb = new StringBuilder(title);
        if (se != null) sb.append(" · ").append(se[0]).append(" сезон, ").append(se[1]).append(" серия");
        Set<String> tags = new LinkedHashSet<>();
        String tag = title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
        if (tag.length() >= 2 && tag.length() <= 30) tags.add("#" + tag);
        tags.add(se != null ? "#сериал" : "#фильм");
        tags.add("#кино");
        tags.add("#моменты");
        tags.add("#рекомендации");
        List<String> t = new ArrayList<>(tags);
        sb.append("\n").append(String.join(" ", t));
        return sb.toString();
    }

    static final Pattern[] EP = {
            Pattern.compile("s(\\d{1,2})[\\s._-]*e(\\d{1,3})"),
            Pattern.compile("(?<!\\d)(\\d{1,2})x(\\d{2,3})(?!\\d)"),
            Pattern.compile("(\\d{1,2})\\s*сезон\\D{0,12}?(\\d{1,3})\\s*сери"),
            Pattern.compile("сезон\\s*(\\d{1,2})\\D{0,12}?сери[яи]\\s*(\\d{1,3})"),
    };
    static final Pattern EP_ONLY = Pattern.compile("(?:сери[яи]\\s*(\\d{1,3}))|(?:(\\d{1,3})\\s*сери)|(?:(?<![a-z])e(?:p|pisode)?\\s*(\\d{1,3})(?!\\d))");

    /** «Breaking.Bad.S01E05.mkv» → {1, 5}; «Во все тяжкие 2 сезон 3 серия» → {2, 3}; «серия 7» → {1, 7}; иначе null. */
    public static int[] episodeOf(String name) {
        String n = (name == null ? "" : name).toLowerCase(Locale.ROOT);
        for (Pattern p : EP) {
            Matcher m = p.matcher(n);
            if (m.find()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        Matcher m = EP_ONLY.matcher(n);
        if (m.find()) {
            for (int g = 1; g <= 3; g++) if (m.group(g) != null) return new int[]{1, Integer.parseInt(m.group(g))};
        }
        return null;
    }
}
