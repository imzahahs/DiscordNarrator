package com.kaigan.bots.narrator;

import java.util.regex.Pattern;

public class ProcessedMessage {

    private static final Pattern wordSplitter = Pattern.compile("[^a-zA-Z]+");
    private static final Pattern whitespaceMatcher = Pattern.compile("\\s+");

    public static String toLowercaseTrimmed(String s) {
        return whitespaceMatcher.matcher(s.toLowerCase()).replaceAll(" ").trim();
    }

    public static String[] toWords(String s) {
        return wordSplitter.split(s);
    }

    public final String raw;
    public final String lowercaseTrimmed;
    public final String[] words;

    public ProcessedMessage(String raw) {
        this.raw = raw;

        lowercaseTrimmed = toLowercaseTrimmed(raw);
        words = toWords(lowercaseTrimmed);
    }

    public boolean hasEdgeWord(String string) {
        for(String word : words) {
            if(word.startsWith(string) || word.endsWith(string))
                return true;
        }
        return false;
    }

    public boolean hasEdgeWord(String[] strings) {
        if(strings == null)
            return false;
        for(String string : strings) {
            if(hasEdgeWord(string))
                return true;
        }
        return false;
    }

    public boolean hasWord(String string) {
        for(String word : words) {
            if(word.equals(string))
                return true;
        }
        return false;
    }

    public boolean hasWord(String[] strings) {
        if(strings == null)
            return false;
        for(String string : strings) {
            if(hasWord(string))
                return true;
        }
        return false;
    }

    public String makeProperSentence() {
        String written = raw.replaceAll("\\s+", " ").replaceAll("```", "").trim();
        if(!written.endsWith("."))
            written = written + ".";            // end with a period
        return written.substring(0, 1).toUpperCase() + written.substring(1);         // Uppercase first letter
    }
}
