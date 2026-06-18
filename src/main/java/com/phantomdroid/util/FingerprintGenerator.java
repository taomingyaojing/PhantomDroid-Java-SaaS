package com.phantomdroid.util;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates realistic, Luhn-validated device fingerprints for Redroid containers.
 * Covers: IMEI, Android ID (SSAID), WiFi MAC, brand/model, battery, SIM info.
 */
public final class FingerprintGenerator {

    private static final String[] BRANDS = {
            "Samsung", "Xiaomi", "OnePlus", "OPPO", "vivo", "Huawei", "Google", "Motorola", "Realme", "Honor"
    };

    private static final String[][] MODELS = {
            {"SM-G998B", "SM-S908B", "SM-A536B"},                    // Samsung
            {"Mi 13", "Mi 14", "Redmi Note 13 Pro", "POCO X6"},      // Xiaomi
            {"OnePlus 12", "OnePlus 11", "OnePlus Nord 3"},           // OnePlus
            {"Find X7", "Reno 11 Pro"},                               // OPPO
            {"X100 Pro", "iQOO 12"},                                  // vivo
            {"P60 Pro", "Mate 60 Pro", "Nova 12 Ultra"},              // Huawei
            {"Pixel 8 Pro", "Pixel 7a"},                              // Google
            {"Edge 50 Pro", "Moto G84"},                              // Motorola
            {"GT 5 Pro", "Realme 12 Pro+"},                           // Realme
            {"Honor Magic6 Pro", "Honor 90"}                          // Honor
    };

    private static final String[] MANUFACTURERS = {
            "samsung", "Xiaomi", "OnePlus", "OPPO", "vivo", "HUAWEI", "Google", "motorola", "realme", "Honor"
    };

    private static final String[] PRODUCTS = {
            "star2qltesq", "q6q", "fuxi", "messi", "PD2304", "MAA-AL00", "husky", "rtwo", "RMX3851", "VER-AN00"
    };

    private static final String[] DEVICES = {
            "star2qlte", "q6q", "fuxi", "messi", "PD2304", "MAA-AL00", "shusky", "rtwo", "RMX3851", "VER"
    };

    private FingerprintGenerator() {}

    /**
     * Generate a complete, valid device fingerprint.
     */
    public static Fingerprint generate() {
        Random rnd = ThreadLocalRandom.current();
        int brandIdx = rnd.nextInt(BRANDS.length);

        String brand = BRANDS[brandIdx];
        String manufacturer = MANUFACTURERS[brandIdx];
        String model = MODELS[brandIdx][rnd.nextInt(MODELS[brandIdx].length)];
        String product = PRODUCTS[brandIdx];
        String device = DEVICES[brandIdx];
        String androidId = generateAndroidId();
        String imei = generateLuhnImei();
        String serial = generateSerial();
        String wifiMac = generateMac();
        String simCountry = pickSimCountry();
        String simOperator = pickSimOperator(simCountry);
        int batteryLevel = 15 + rnd.nextInt(76);   // 15-90%

        String buildFingerprint = manufacturer + "/" + product + "/" + device + ":11/RP1A.200720.011/" + serial + ":user/release-keys";

        return new Fingerprint(brand, manufacturer, model, product, device, androidId,
                imei, serial, wifiMac, simCountry, simOperator, batteryLevel, buildFingerprint);
    }

    /**
     * Generate a 16-char hex Android ID (SSAID).
     * Only uses characters a-f,0-9; never starts with 0.
     */
    private static String generateAndroidId() {
        Random rnd = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(16);
        sb.append(Integer.toHexString(1 + rnd.nextInt(15)));  // first char != 0
        for (int i = 1; i < 16; i++) {
            sb.append(Integer.toHexString(rnd.nextInt(16)));
        }
        return sb.toString();
    }

    /**
     * Generate a 15-digit IMEI that passes Luhn checksum validation.
     */
    private static String generateLuhnImei() {
        Random rnd = ThreadLocalRandom.current();
        int[] digits = new int[15];
        // IMEI prefixes: 01 (Telia), 35 (Nokia/British), 86 (China), 49 (Telenor)
        int prefix = switch (rnd.nextInt(4)) {
            case 0 -> 1;
            case 1 -> 35;
            case 2 -> 86;
            case 3 -> 49;
            default -> 35;
        };
        digits[0] = prefix / 10;
        digits[1] = prefix % 10;
        for (int i = 2; i < 14; i++) {
            digits[i] = rnd.nextInt(10);
        }
        // Calculate Luhn checksum for digit 14
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int d = digits[i];
            if (i % 2 == 0) {  // double every second digit (even positions)
                d *= 2;
                if (d >= 10) d = d - 9;
            }
            sum += d;
        }
        int checksum = (10 - (sum % 10)) % 10;
        digits[14] = checksum;

        StringBuilder sb = new StringBuilder(15);
        for (int d : digits) sb.append(d);
        return sb.toString();
    }

    private static String generateSerial() {
        Random rnd = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            char c = rnd.nextBoolean()
                    ? (char) ('A' + rnd.nextInt(26))
                    : (char) ('0' + rnd.nextInt(10));
            sb.append(c);
        }
        return sb.toString();
    }

    private static String generateMac() {
        Random rnd = ThreadLocalRandom.current();
        // Use locally-administered MAC range (02:xx:xx:xx:xx:xx)
        StringBuilder sb = new StringBuilder("02");
        for (int i = 1; i < 6; i++) {
            sb.append(':');
            sb.append(String.format("%02x", rnd.nextInt(256)));
        }
        return sb.toString();
    }

    private static String pickSimCountry() {
        String[] countries = {"us", "gb", "sg", "jp", "de", "fr", "kr"};
        return countries[ThreadLocalRandom.current().nextInt(countries.length)];
    }

    private static String pickSimOperator(String country) {
        return switch (country) {
            case "us" -> "310410";  // AT&T
            case "gb" -> "23430";   // EE UK
            case "sg" -> "52501";   // Singtel
            case "jp" -> "44010";   // docomo
            case "de" -> "26201";   // Telekom DE
            case "fr" -> "20801";   // Orange FR
            case "kr" -> "45002";   // KT
            default -> "310410";
        };
    }

    /**
     * Immutable fingerprint data object.
     */
    public static class Fingerprint {
        private final String brand;
        private final String manufacturer;
        private final String model;
        private final String product;
        private final String device;
        private final String androidId;
        private final String imei;
        private final String serial;
        private final String wifiMac;
        private final String simCountry;
        private final String simOperator;
        private final int batteryLevel;
        private final String buildFingerprint;

        public Fingerprint(String brand, String manufacturer, String model, String product,
                           String device, String androidId, String imei, String serial,
                           String wifiMac, String simCountry, String simOperator,
                           int batteryLevel, String buildFingerprint) {
            this.brand = brand;
            this.manufacturer = manufacturer;
            this.model = model;
            this.product = product;
            this.device = device;
            this.androidId = androidId;
            this.imei = imei;
            this.serial = serial;
            this.wifiMac = wifiMac;
            this.simCountry = simCountry;
            this.simOperator = simOperator;
            this.batteryLevel = batteryLevel;
            this.buildFingerprint = buildFingerprint;
        }

        public String getBrand() { return brand; }
        public String getManufacturer() { return manufacturer; }
        public String getModel() { return model; }
        public String getProduct() { return product; }
        public String getDevice() { return device; }
        public String getAndroidId() { return androidId; }
        public String getImei() { return imei; }
        public String getSerial() { return serial; }
        public String getWifiMac() { return wifiMac; }
        public String getSimCountry() { return simCountry; }
        public String getSimOperator() { return simOperator; }
        public int getBatteryLevel() { return batteryLevel; }
        public String getBuildFingerprint() { return buildFingerprint; }
    }
}
