package com.kaigan.bots.narrator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BotMain {
    private static final Logger log = LogManager.getLogger("BotMain");

    private static final String BOT_TOKEN = "NjgyOTc5Mzg1MDAyNjg4NTMx.Xlk4Hg.oSMWniZ9Ofm0pZh68eN_fcBs3KE";

    private static final String DISCORD_SERVER = "Kaigan Games";


    public static void main(String[] args) {
        log.info("Narrator Builder started");

        // Discordia builder
        NarratorBuilder builder = new NarratorBuilder("main.xlsx", "19htIKvOuQbdjt3RJp-9I1tp7tRUIoWSKvUeJOKZhJ_E", "main.v1");
        builder.build(BOT_TOKEN, DISCORD_SERVER);

        log.info("Narrator Builder ended");
    }

}
