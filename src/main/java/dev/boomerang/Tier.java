package dev.boomerang;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/** Три вида бумеранга. */
enum Tier {
    COPPER("copper", "Медный бумеранг", NamedTextColor.GOLD, Material.COPPER_INGOT),
    IRON("iron", "Железный бумеранг", NamedTextColor.WHITE, Material.IRON_INGOT),
    DIAMOND("diamond", "Алмазный бумеранг", NamedTextColor.AQUA, Material.DIAMOND);

    final String id;
    final String title;
    final TextColor color;
    /** Материал для крафта, а также внешний вид предмета без ресурспака. */
    final Material ingredient;

    Tier(String id, String title, TextColor color, Material ingredient) {
        this.id = id;
        this.title = title;
        this.color = color;
        this.ingredient = ingredient;
    }

    static Tier byId(String id) {
        for (Tier t : values()) if (t.id.equals(id)) return t;
        return null;
    }
}
