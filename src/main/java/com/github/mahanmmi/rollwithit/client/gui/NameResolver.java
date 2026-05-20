package com.github.mahanmmi.rollwithit.client.gui;

import iskallia.vault.client.gui.screen.bounty.BountyScreen;
import iskallia.vault.core.world.data.entity.EntityPredicate;
import iskallia.vault.util.GroupUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the raw ResourceLocations / objective strings stored in {@code BountyDatabase} into the
 * human-readable display text Vault Hunters itself shows on the bounty table.
 * <ul>
 *     <li>Task types use VH's {@code BountyScreen.OBJECTIVE_NAME}-style "Kill Entity" labels via a
 *         small built-in map for the 6 known types (fallback: prettified path).</li>
 *     <li>Task values dispatch by shape: {@code @...} → entity group (via
 *         {@link GroupUtils#getEntityName}), known objective key → {@link BountyScreen#OBJECTIVE_NAME},
 *         parseable {@link ResourceLocation} → first matching registry of
 *         entity / item / block.</li>
 *     <li>Reward items resolve through the Forge item registry's display name.</li>
 * </ul>
 * All lookups fall back to the raw input so the user is never left with a blank row.
 */
public final class NameResolver {

    private NameResolver() {}

    // ---- task types ----

    public static String taskTypeName(ResourceLocation type) {
        // VH's task types live in iskallia.vault.bounty.TaskRegistry; they don't have a public
        // localization key, so we use the same labels VH paints on the bounty cards.
        String path = type.getPath();
        return switch (path) {
            case "kill_entity"     -> "Kill Entity";
            case "damage_entity"   -> "Damage Entity";
            case "completion"      -> "Complete Objective";
            case "item_discovery"  -> "Discover Item";
            case "item_submission" -> "Submit Item";
            case "mining"          -> "Mine Block";
            default -> prettify(path);
        };
    }

    // ---- task values ----

    public static String taskValueName(String raw) {
        if (raw == null || raw.isEmpty()) return "?";

        // 1. Entity group like "@the_vault:assassin" — ask VH.
        // VH's getFilterByName(name) hard-prefixes "the_vault:", so a fully-qualified key would
        // produce the invalid id "the_vault:the_vault:assassin". Route those through
        // getFilterById(ResourceLocation) instead.
        if (raw.startsWith("@")) {
            String key = raw.substring(1);
            Optional<EntityPredicate> pred;
            ResourceLocation rl = key.contains(":") ? ResourceLocation.tryParse(key) : null;
            if (rl != null) {
                pred = GroupUtils.getFilterById(rl);
            } else {
                pred = GroupUtils.getFilterByName(key);
            }
            if (pred.isPresent()) {
                Component c = GroupUtils.getEntityName(pred.get());
                if (c != null) return c.getString();
            }
            return prettify(stripNamespace(key));
        }

        // 2. Known completion objective like "obelisk", "elixir".
        Component objective = BountyScreen.OBJECTIVE_NAME.get(raw);
        if (objective != null) return objective.getString();

        // 3. NBT-shaped EntityPredicate toString like {id:"minecraft:elder_guardian", ...}.
        // VH stores vanilla-entity kill targets as a PartialCompoundNbt-style predicate whose
        // String.valueOf() emits a curly-braced NBT compound; we extract its id and resolve it
        // through the entity registry the same way as a bare ResourceLocation.
        ResourceLocation nbtEntityId = extractEntityIdFromNbtForm(raw);
        if (nbtEntityId != null && ForgeRegistries.ENTITIES.containsKey(nbtEntityId)) {
            EntityType<?> et = ForgeRegistries.ENTITIES.getValue(nbtEntityId);
            if (et != null) return et.getDescription().getString();
        }

        // 4. Try as ResourceLocation — entity, item, then block. NOTE: Forge registries return
        // the registry's *default value* on miss (entity=minecraft:pig, item/block=minecraft:air),
        // so we must containsKey() first to detect genuine matches.
        ResourceLocation rl = ResourceLocation.tryParse(raw);
        if (rl != null) {
            if (ForgeRegistries.ENTITIES.containsKey(rl)) {
                EntityType<?> et = ForgeRegistries.ENTITIES.getValue(rl);
                if (et != null) return et.getDescription().getString();
            }
            if (ForgeRegistries.ITEMS.containsKey(rl)) {
                Item it = ForgeRegistries.ITEMS.getValue(rl);
                if (it != null) return it.getDescription().getString();
            }
            if (ForgeRegistries.BLOCKS.containsKey(rl)) {
                Block bl = ForgeRegistries.BLOCKS.getValue(rl);
                if (bl != null) return bl.getName().getString();
            }
            return prettify(rl.getPath());
        }

        return prettify(raw);
    }

    // ---- reward items ----

    public static String rewardItemName(ResourceLocation itemId) {
        if (ForgeRegistries.ITEMS.containsKey(itemId)) {
            Item it = ForgeRegistries.ITEMS.getValue(itemId);
            if (it != null) return it.getDescription().getString();
        }
        return prettify(itemId.getPath());
    }

    // ---- helpers ----

    /** "ancient_debris" → "Ancient Debris" */
    private static String prettify(String snake) {
        if (snake == null || snake.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(snake.length());
        boolean upper = true;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_' || c == '-' || c == ' ' || c == '.') {
                sb.append(' ');
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Matches an {@code id:"<namespace>:<path>"} field anywhere inside a NBT-compound-style
     * string. Quotes around both the key and value are optional so {@code id:"minecraft:pig"},
     * {@code "id":"minecraft:pig"} and {@code id:minecraft:pig} all parse.
     */
    private static final Pattern NBT_ENTITY_ID = Pattern.compile(
            "\"?id\"?\\s*:\\s*\"?([a-z0-9_.-]+:[a-z0-9_./-]+)\"?");

    private static ResourceLocation extractEntityIdFromNbtForm(String raw) {
        if (raw == null || raw.length() < 5 || raw.charAt(0) != '{') return null;
        Matcher m = NBT_ENTITY_ID.matcher(raw);
        return m.find() ? ResourceLocation.tryParse(m.group(1)) : null;
    }

    private static String stripNamespace(String s) {
        int colon = s.indexOf(':');
        return colon < 0 ? s : s.substring(colon + 1);
    }

    /** Convenience wrapper used by Screen labels. */
    public static Component asComponent(String s) {
        return new TextComponent(s);
    }
}
