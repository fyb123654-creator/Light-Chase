package com.lightchase.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.lightchase.contracts.GroundMaterial;
import com.lightchase.contracts.MapPoint;
import com.lightchase.contracts.MaterialPoint;
import com.lightchase.contracts.PlayerView;
import com.lightchase.data.DataFiles;

import java.util.Map;

/**
 * The task props: the loot on the floor, the deposit hatches and the cellar doors.
 *
 * <p>They share three fade tables with the minimap and with each other, so they travel together.
 * Extracted from {@code WorldRenderer}, whose job is the room itself rather than the things in it.
 */
final class TaskArt {

    static final Color DROP = new Color(0.180f, 0.690f, 0.569f, 1f);
    private static final Color EXIT_SHUT = new Color(0.400f, 0.310f, 0.125f, 1f);
    static final Color EXIT_OPEN = new Color(0.965f, 0.729f, 0.184f, 1f);
    private static final int LOOT_PHONE = 0;
    private static final int LOOT_DIAMOND = 1;
    private static final int LOOT_PAINTING = 2;
    private static final int LOOT_CASH = 3;
    private static final int LOOT_RING = 4;
    static final Color MATERIAL = new Color(0.949f, 0.741f, 0.239f, 1f);

    private final ShapeRenderer shapes;
    private final DataFiles data;
    private final Map<String, float[]> dropOpen;
    private final Map<String, float[]> exitOpen;
    private final Map<String, float[]> lootFade;

    TaskArt(ShapeRenderer shapes, DataFiles data, Map<String, float[]> dropOpen,
            Map<String, float[]> exitOpen, Map<String, float[]> lootFade) {
        this.shapes = shapes;
        this.data = data;
        this.dropOpen = dropOpen;
        this.exitOpen = exitOpen;
        this.lootFade = lootFade;
    }

    void drawLoot(float x, float y, int type, float fade) {
        float s = 0.30f;
        // Shadow and glow follow the silhouette's own footprint.
        float gw;
        float gh;
        float gr;
        switch (type) {
            case LOOT_PHONE:    gw = s * 1.04f; gh = s * 1.84f; gr = s * 0.24f; break;
            case LOOT_PAINTING: gw = s * 1.84f; gh = s * 1.44f; gr = s * 0.14f; break;
            case LOOT_CASH:     gw = s * 1.80f; gh = s * 1.04f; gr = s * 0.14f; break;
            case LOOT_DIAMOND:  gw = s * 1.64f; gh = s * 1.90f; gr = s * 0.52f; break;
            default:            gw = s * 1.30f; gh = s * 1.60f; gr = s * 0.50f; break;
        }
        shapes.setColor(0f, 0f, 0f, 0.30f * fade);
        ShapeKit.rounded(shapes, x - gw * 0.58f, y - gh * 0.58f - 0.04f, gw * 1.16f, gh * 1.16f, gr * 1.16f,
                new Color(0f, 0f, 0f, 0.30f * fade));

        switch (type) {
            case LOOT_DIAMOND: {
                shapes.setColor(1f, 1f, 1f, 0.85f * fade);
                shapes.triangle(x, y + s * 1.02f, x - s * 0.82f, y + s * 0.14f, x + s * 0.82f, y + s * 0.14f);
                shapes.triangle(x - s * 0.82f, y + s * 0.14f, x + s * 0.82f, y + s * 0.14f, x, y - s * 1.0f);
                shapes.setColor(0.36f, 0.72f, 1f, fade);
                shapes.triangle(x, y + s * 0.92f, x - s * 0.70f, y + s * 0.18f, x + s * 0.70f, y + s * 0.18f);
                shapes.setColor(0.14f, 0.38f, 0.78f, fade);
                shapes.triangle(x - s * 0.70f, y + s * 0.18f, x + s * 0.70f, y + s * 0.18f, x, y - s * 0.86f);
                shapes.setColor(1f, 1f, 1f, 0.85f * fade);
                shapes.triangle(x, y + s * 0.80f, x - s * 0.18f, y + s * 0.36f, x + s * 0.12f, y + s * 0.32f);
                break;
            }
            case LOOT_PHONE: {
                shapes.setColor(0.90f, 0.92f, 0.96f, fade);
                ShapeKit.rounded(shapes, x - s * 0.52f, y - s * 0.92f, s * 1.04f, s * 1.84f, s * 0.22f,
                        new Color(0.90f, 0.92f, 0.96f, fade));
                shapes.setColor(0.10f, 0.12f, 0.16f, fade);
                ShapeKit.rounded(shapes, x - s * 0.42f, y - s * 0.74f, s * 0.84f, s * 1.40f, s * 0.10f,
                        new Color(0.10f, 0.12f, 0.16f, fade));
                shapes.setColor(0.24f, 0.52f, 0.86f, fade);
                ShapeKit.rounded(shapes, x - s * 0.38f, y - s * 0.70f, s * 0.76f, s * 1.30f, s * 0.08f,
                        new Color(0.24f, 0.52f, 0.86f, fade));
                shapes.setColor(1f, 1f, 1f, 0.55f * fade);
                shapes.rect(x - s * 0.30f, y + s * 0.40f, s * 0.42f, s * 0.06f);
                shapes.setColor(0.86f, 0.88f, 0.92f, fade);
                shapes.circle(x, y + s * 0.76f, s * 0.07f, 12);
                break;
            }
            case LOOT_PAINTING: {
                shapes.setColor(0.75f, 0.60f, 0.28f, fade);
                ShapeKit.rounded(shapes, x - s * 0.92f, y - s * 0.72f, s * 1.84f, s * 1.44f, s * 0.10f,
                        new Color(0.75f, 0.60f, 0.28f, fade));
                shapes.setColor(0.16f, 0.30f, 0.26f, fade);
                shapes.rect(x - s * 0.76f, y - s * 0.56f, s * 1.52f, s * 1.12f);
                shapes.setColor(0.30f, 0.52f, 0.40f, fade);
                shapes.triangle(x - s * 0.70f, y - s * 0.50f, x + s * 0.10f, y + s * 0.34f,
                        x + s * 0.70f, y - s * 0.50f);
                shapes.setColor(0.86f, 0.80f, 0.52f, fade);
                shapes.circle(x + s * 0.34f, y + s * 0.28f, s * 0.16f, 16);
                break;
            }
            case LOOT_CASH: {
                shapes.setColor(0.86f, 0.92f, 0.84f, fade);
                ShapeKit.rounded(shapes, x - s * 0.90f, y - s * 0.44f, s * 1.80f, s * 0.92f, s * 0.10f,
                        new Color(0.86f, 0.92f, 0.84f, fade));
                shapes.setColor(0.60f, 0.78f, 0.62f, fade);
                ShapeKit.rounded(shapes, x - s * 0.90f, y - s * 0.62f, s * 1.80f, s * 0.42f, s * 0.08f,
                        new Color(0.60f, 0.78f, 0.62f, fade));
                shapes.setColor(0.20f, 0.42f, 0.26f, fade);
                shapes.circle(x, y - s * 0.02f, s * 0.24f, 18);
                shapes.setColor(0.86f, 0.92f, 0.84f, fade);
                shapes.circle(x, y - s * 0.02f, s * 0.13f, 14);
                break;
            }
            default: {
                shapes.setColor(1f, 0.86f, 0.42f, fade);
                shapes.circle(x, y - s * 0.18f, s * 0.60f, 24);
                shapes.setColor(0.16f, 0.16f, 0.20f, fade);
                shapes.circle(x, y - s * 0.18f, s * 0.42f, 22);
                shapes.setColor(1f, 0.92f, 0.66f, fade);
                shapes.rect(x - s * 0.16f, y + s * 0.22f, s * 0.32f, s * 0.34f);
                shapes.setColor(0.55f, 0.86f, 1f, fade);
                shapes.triangle(x, y + s * 0.92f, x - s * 0.22f, y + s * 0.56f, x + s * 0.22f, y + s * 0.56f);
                shapes.setColor(1f, 1f, 1f, 0.9f * fade);
                shapes.triangle(x, y + s * 0.92f, x - s * 0.08f, y + s * 0.62f, x + s * 0.10f, y + s * 0.60f);
                break;
            }
        }
    }

    int lootType(String materialId) {
        for (int i = 0; i < data.map.materials.size(); i++) {
            if (data.map.materials.get(i).id.equals(materialId)) return i % 5;
        }
        return LOOT_PHONE;
    }

    private void drawDropPoint(float x, float y, float open) {
        float s = 0.40f;
        Color shut = new Color(0.62f, 0.50f, 0.24f, 1f);
        Color live = new Color(0.36f, 0.92f, 0.70f, 1f);
        Color rim = new Color(shut).lerp(live, open);

        // Panel shadow.
        shapes.setColor(0f, 0f, 0f, 0.34f);
        ShapeKit.rounded(shapes, x - s * 1.14f, y - s * 1.18f, s * 2.28f, s * 2.28f, s * 0.34f,
                new Color(0f, 0f, 0f, 0.34f));

        // Base plate, then a darker bezel so the panel has thickness.
        shapes.setColor(0.20f, 0.21f, 0.23f, 1f);
        ShapeKit.rounded(shapes, x - s * 1.10f, y - s * 1.10f, s * 2.20f, s * 2.20f, s * 0.32f,
                new Color(0.20f, 0.21f, 0.23f, 1f));
        shapes.setColor(0.13f, 0.14f, 0.16f, 1f);
        ShapeKit.rounded(shapes, x - s * 0.98f, y - s * 0.98f, s * 1.96f, s * 1.96f, s * 0.28f,
                new Color(0.13f, 0.14f, 0.16f, 1f));

        // Corner bolts.
        shapes.setColor(0.42f, 0.43f, 0.46f, 1f);
        float b = s * 0.82f;
        shapes.circle(x - b, y - b, s * 0.07f, 12);
        shapes.circle(x + b, y - b, s * 0.07f, 12);
        shapes.circle(x - b, y + b, s * 0.07f, 12);
        shapes.circle(x + b, y + b, s * 0.07f, 12);

        // Recessed chute: the hole the loot goes into.
        shapes.setColor(0.045f, 0.055f, 0.070f, 1f);
        ShapeKit.rounded(shapes, x - s * 0.62f, y - s * 0.48f, s * 1.24f, s * 0.96f, s * 0.16f,
                new Color(0.045f, 0.055f, 0.070f, 1f));
        shapes.setColor(rim.r, rim.g, rim.b, 0.30f + 0.70f * open);
        ShapeKit.rounded(shapes, x - s * 0.52f, y - s * 0.38f, s * 1.04f, s * 0.76f, s * 0.13f,
                new Color(rim.r, rim.g, rim.b, 0.30f + 0.70f * open));

        // Lid: closed covers the chute, open slides up and shrinks a little.
        float lift = s * 0.62f * open;
        float shrink = 1f - 0.18f * open;
        shapes.setColor(0f, 0f, 0f, 0.30f);
        ShapeKit.rounded(shapes, x - s * 0.66f * shrink, y - s * 0.52f * shrink + lift - 0.02f,
                s * 1.32f * shrink, s * 1.04f * shrink, s * 0.18f,
                new Color(0f, 0f, 0f, 0.30f));
        shapes.setColor(0.34f, 0.35f, 0.38f, 1f);
        ShapeKit.rounded(shapes, x - s * 0.64f * shrink, y - s * 0.50f * shrink + lift,
                s * 1.28f * shrink, s * 1.00f * shrink, s * 0.17f,
                new Color(0.34f, 0.35f, 0.38f, 1f));
        shapes.setColor(1f, 1f, 1f, 0.10f);
        shapes.rect(x - s * 0.64f * shrink, y + s * 0.38f * shrink + lift,
                s * 1.28f * shrink, s * 0.06f);

        // Slot on the lid: the label slot, lit when this is the right destination.
        shapes.setColor(0.10f, 0.11f, 0.13f, 1f);
        ShapeKit.rounded(shapes, x - s * 0.34f * shrink, y + s * 0.12f * shrink + lift,
                s * 0.68f * shrink, s * 0.16f * shrink, s * 0.06f,
                new Color(0.10f, 0.11f, 0.13f, 1f));
        shapes.setColor(rim.r, rim.g, rim.b, 0.25f + 0.75f * open);
        ShapeKit.rounded(shapes, x - s * 0.30f * shrink, y + s * 0.155f * shrink + lift,
                s * 0.60f * shrink, s * 0.09f * shrink, s * 0.04f,
                new Color(rim.r, rim.g, rim.b, 0.25f + 0.75f * open));
    }

    private void drawExit(float x, float y, float openAmount) {
        float hw = 0.46f;
        float hh = 0.42f;

        shapes.setColor(0f, 0f, 0f, 0.40f);
        ShapeKit.rounded(shapes, x - hw - 0.06f, y - hh - 0.08f, hw * 2f + 0.12f, hh * 2f + 0.12f, 0.08f,
                new Color(0f, 0f, 0f, 0.40f));

        shapes.setColor(0.30f, 0.31f, 0.33f, 1f);
        ShapeKit.rounded(shapes, x - hw, y - hh, hw * 2f, hh * 2f, 0.06f, new Color(0.30f, 0.31f, 0.33f, 1f));
        shapes.setColor(1f, 1f, 1f, 0.10f);
        shapes.rect(x - hw + 0.04f, y + hh - 0.05f, hw * 2f - 0.08f, 0.03f);

        shapes.setColor(0.030f, 0.038f, 0.050f, 1f);
        ShapeKit.rounded(shapes, x - hw + 0.08f, y - hh + 0.08f, hw * 2f - 0.16f, hh * 2f - 0.16f, 0.04f,
                new Color(0.030f, 0.038f, 0.050f, 1f));
        for (int i = 0; i < 3; i++) {
            float inset = 0.10f + i * 0.09f;
            shapes.setColor(1f, 1f, 1f, 0.16f - i * 0.045f);
            shapes.rect(x - hw + inset + 0.06f, y - hh + inset + 0.06f,
                    hw * 2f - (inset + 0.06f) * 2f, 0.035f);
        }

        float lift = 1f - openAmount * 0.72f;
        float lidTop = y + hh - 0.02f;
        float lidH = Math.max(0.02f, (hh * 2f - 0.10f) * lift);
        Color lid = new Color(0.42f, 0.33f, 0.20f, 1f)
                .lerp(new Color(0.62f, 0.48f, 0.22f, 1f), openAmount);
        shapes.setColor(0f, 0f, 0f, 0.30f);
        ShapeKit.rounded(shapes, x - hw + 0.05f, lidTop - lidH - 0.03f, hw * 2f - 0.10f, lidH, 0.05f,
                new Color(0f, 0f, 0f, 0.30f));
        shapes.setColor(lid);
        ShapeKit.rounded(shapes, x - hw + 0.05f, lidTop - lidH, hw * 2f - 0.10f, lidH, 0.05f, lid);
        shapes.setColor(0f, 0f, 0f, 0.22f);
        for (int i = 1; i < 4; i++) {
            float px = x - hw + 0.05f + (hw * 2f - 0.10f) * i / 4f;
            shapes.rect(px, lidTop - lidH + 0.02f, 0.02f, Math.max(0.02f, lidH - 0.04f));
        }
        if (lidH > 0.12f) {
            shapes.setColor(0.14f, 0.14f, 0.16f, 1f);
            shapes.circle(x, lidTop - lidH * 0.55f, 0.055f, 14);
            shapes.setColor(0.62f, 0.63f, 0.66f, 1f);
            shapes.circle(x, lidTop - lidH * 0.55f, 0.03f, 12);
        }

        if (openAmount > 0.02f) {
            shapes.setColor(EXIT_OPEN.r, EXIT_OPEN.g, EXIT_OPEN.b, 0.16f * openAmount);
            ShapeKit.rounded(shapes, x - hw + 0.10f, y - hh + 0.10f, hw * 2f - 0.20f, hh * 2f - 0.20f, 0.04f,
                    new Color(EXIT_OPEN.r, EXIT_OPEN.g, EXIT_OPEN.b, 0.16f * openAmount));
            shapes.setColor(EXIT_OPEN.r, EXIT_OPEN.g, EXIT_OPEN.b, 0.55f * openAmount);
            shapes.rect(x - hw + 0.14f, y + hh - 0.12f, hw * 2f - 0.28f, 0.035f);
        }
    }

    void drawPoints(PlayerView view, float dt) {
        String usable = usableDelivery(view);
        for (MapPoint point : data.map.deliveries) {
            float[] state = dropOpen.get(point.id);
            if (state == null) {
                state = new float[]{0f};
                dropOpen.put(point.id, state);
            }
            float target = usable != null && usable.equals(point.id) ? 1f : 0f;
            state[0] = UiAnim.approach(state[0], target, dt, 5f);
            drawDropPoint((float) point.x, (float) point.y, state[0]);
        }
        boolean open = view != null && view.exitsOpen;
        for (MapPoint point : data.map.exits) {
            float[] state = exitOpen.get(point.id);
            if (state == null) {
                state = new float[]{0f};
                exitOpen.put(point.id, state);
            }
            state[0] = UiAnim.approach(state[0], open ? 1f : 0f, dt, 4.5f);
            drawExit((float) point.x, (float) point.y, state[0]);
        }
    }

    String usableDelivery(PlayerView view) {
        if (view == null || view.thief == null || view.thief.carriedMaterialId == null) return null;
        for (MaterialPoint material : data.map.materials) {
            if (material.id.equals(view.thief.carriedMaterialId)) return material.deliveryId;
        }
        return null;
    }
}
