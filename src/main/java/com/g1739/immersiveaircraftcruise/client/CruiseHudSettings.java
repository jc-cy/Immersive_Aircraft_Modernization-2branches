package com.g1739.immersiveaircraftcruise.client;

import net.minecraft.util.Mth;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class CruiseHudSettings {
    public static final int WIDTH = 176;
    public static final int HEIGHT = 92;

    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("immersive_aircraft_cruise_hud.properties");
    private static boolean loaded;
    private static boolean customPosition;
    private static double xRatio;
    private static double yRatio;

    private CruiseHudSettings() {
    }

    public static int x(int screenWidth) {
        load();
        int maxX = maxX(screenWidth);
        if (customPosition) {
            return Mth.clamp((int) Math.round(xRatio * maxX), 0, maxX);
        }
        return Mth.clamp(screenWidth - WIDTH - 6, 0, maxX);
    }

    public static int y(int screenHeight) {
        load();
        int maxY = maxY(screenHeight);
        if (customPosition) {
            return Mth.clamp((int) Math.round(yRatio * maxY), 0, maxY);
        }
        return Mth.clamp(screenHeight - HEIGHT - 44, 0, maxY);
    }

    public static void setPosition(int x, int y, int screenWidth, int screenHeight) {
        load();
        int maxX = maxX(screenWidth);
        int maxY = maxY(screenHeight);
        customPosition = true;
        xRatio = maxX <= 0 ? 0.0d : (double) Mth.clamp(x, 0, maxX) / maxX;
        yRatio = maxY <= 0 ? 0.0d : (double) Mth.clamp(y, 0, maxY) / maxY;
    }

    public static void save() {
        load();
        Properties properties = new Properties();
        properties.setProperty("customPosition", Boolean.toString(customPosition));
        properties.setProperty("xRatio", Double.toString(xRatio));
        properties.setProperty("yRatio", Double.toString(yRatio));
        try {
            Files.createDirectories(PATH.getParent());
            try (OutputStream output = Files.newOutputStream(PATH)) {
                properties.store(output, "Immersive Aircraft Cruise HUD settings");
            }
        } catch (IOException ignored) {
        }
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(PATH)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(PATH)) {
            properties.load(input);
            customPosition = Boolean.parseBoolean(properties.getProperty("customPosition", "false"));
            xRatio = parseRatio(properties.getProperty("xRatio"), 1.0d);
            yRatio = parseRatio(properties.getProperty("yRatio"), 1.0d);
        } catch (IOException ignored) {
        }
    }

    private static double parseRatio(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Mth.clamp(Double.parseDouble(value), 0.0d, 1.0d);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int maxX(int screenWidth) {
        return Math.max(0, screenWidth - WIDTH);
    }

    private static int maxY(int screenHeight) {
        return Math.max(0, screenHeight - HEIGHT);
    }
}
