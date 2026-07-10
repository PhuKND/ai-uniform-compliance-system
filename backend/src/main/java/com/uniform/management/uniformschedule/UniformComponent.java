package com.uniform.management.uniformschedule;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public enum UniformComponent {
    AO_SO_MI_TRANG("ao_so_mi_trang", "áo sơ mi trắng"),
    AO_DOAN_THANH_NIEN("ao_doan_thanh_nien", "áo đoàn thanh niên"),
    QUAN_TAY_DAI_DEN("quan_tay_dai_den", "quần tây dài đen"),
    KHAN_QUANG_DO("khan_quang_do", "khăn quàng đỏ"),
    QUAN_SHORT_TAY_DEN("quan_short_tay_den", "quần short đen"),
    QUAN_DAI_TRANG("quan_dai_trang", "quần dài trắng");

    private final String key;
    private final String label;

    UniformComponent(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public static List<UniformComponent> canonicalValues() {
        return List.of(values());
    }

    public static List<String> canonicalKeys() {
        return canonicalValues().stream().map(UniformComponent::key).toList();
    }

    public static Optional<UniformComponent> fromKey(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String raw = value.trim();
        return Arrays.stream(values())
                .filter(component -> component.key.equals(raw)
                        || component.name().equalsIgnoreCase(raw)
                        || normalized(component.label).equals(normalized(raw))
                        || aliasMatches(component, normalized(raw)))
                .findFirst();
    }

    private static boolean aliasMatches(UniformComponent component, String normalized) {
        return switch (component) {
            case AO_SO_MI_TRANG -> List.of("ao so mi trang", "white shirt", "white school shirt").contains(normalized);
            case AO_DOAN_THANH_NIEN -> List.of("ao doan thanh nien", "youth union shirt", "blue youth union shirt").contains(normalized);
            case QUAN_TAY_DAI_DEN -> List.of("quan tay dai den", "black trousers", "long black trousers").contains(normalized);
            case KHAN_QUANG_DO -> List.of("khan quang do", "red scarf", "red school scarf").contains(normalized);
            case QUAN_SHORT_TAY_DEN -> List.of("quan short den", "quan short tay den", "black shorts", "black school shorts").contains(normalized);
            case QUAN_DAI_TRANG -> List.of("quan dai trang", "white trousers", "white long trousers").contains(normalized);
        };
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace("Đ", "D")
                .replace("đ", "d")
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
