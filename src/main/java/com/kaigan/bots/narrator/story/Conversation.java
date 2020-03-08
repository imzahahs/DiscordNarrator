package com.kaigan.bots.narrator.story;

import com.kaigan.bots.narrator.script.Operation;

import java.util.ArrayList;
import java.util.List;

public class Conversation {
    public final List<String> tags = new ArrayList<>();
    public final List<UserMessage> userMessages = new ArrayList<>();
    public boolean isUserIgnored = false;

    public final List<SenderMessage> senderMessages = new ArrayList<>();

    public final List<String> tagsToUnlock = new ArrayList<>();
    public final List<String> tagsToLock = new ArrayList<>();

    public Operation[] script;
}
