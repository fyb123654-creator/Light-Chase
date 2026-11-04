package com.lightchase.client;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Everything a page needs to draw itself, and nothing else.
 *
 * <p>Pages are static draw functions: they receive this context, they read their own state from the
 * arguments they are given, and they own no fields. That keeps the whole UI under one update loop in
 * {@code LightChaseApp} while each page's layout lives in its own file.
 */
final class PageContext {

    final Batch batch;
    final UiFonts fonts;
    final UiRound round;
    final Widgets widgets;
    /** Width of the UI world; the viewport extends horizontally, so anchors use this. */
    final float worldWidth;

    PageContext(Batch batch, UiFonts fonts, UiRound round, Widgets widgets, float worldWidth) {
        this.batch = batch;
        this.fonts = fonts;
        this.round = round;
        this.widgets = widgets;
        this.worldWidth = worldWidth;
    }
}
