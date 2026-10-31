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

    public static String make(String fileName) {
        String n = fileName == null ? "" : fileName;
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        int[] se = Tmdb.episodeOf(n);
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
}
