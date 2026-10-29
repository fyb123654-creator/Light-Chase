package com.lightchase.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;

/**
 * The five sizes the layout needs, from one rounded face.
 *
 * <p>One family keeps the screen coherent and keeps the APK small: a single OFL font covers the
 * masthead, the role names and the 14 px captions, so the hierarchy comes from size and colour
 * rather than from mixing typefaces.
 *
 * <p>Loading is defensive: without the TTF asset or the freetype native every field falls back to
 * the built-in bitmap font, which is ugly but keeps the app from crashing on launch.
 */
final class UiFonts {

    private static final String PATH = "fonts/NunitoSans-SemiBold.ttf";

    /** Masthead. */
    final BitmapFont display;
    /** Role names. */
    final BitmapFont displayMid;
    /** Room id. */
    final BitmapFont displaySmall;
    /** Labels, hints, footer. */
    final BitmapFont mono;
    /** Emphasis (ready state, current player). */
    final BitmapFont monoBold;
    /** Button labels. */
    final BitmapFont monoBig;
    /** The primary action: bigger than the labels, still under the role names. */
    final BitmapFont cta;
    /** One number, seen from across the room: the countdown. */
    final BitmapFont huge;

    private final BitmapFont[] all;

    private UiFonts(BitmapFont display, BitmapFont displayMid, BitmapFont displaySmall,
                    BitmapFont mono, BitmapFont monoBold, BitmapFont monoBig, BitmapFont cta,
                    BitmapFont huge) {
        this.display = display;
        this.displayMid = displayMid;
        this.displaySmall = displaySmall;
        this.mono = mono;
        this.monoBold = monoBold;
        this.monoBig = monoBig;
        this.cta = cta;
        this.huge = huge;
        this.all = new BitmapFont[]{display, displayMid, displaySmall, mono, monoBold, monoBig, cta, huge};
    }

    static UiFonts load() {
        try {
            BitmapFont display = generate(UiTheme.SIZE_TITLE);
            BitmapFont mid = generate(UiTheme.SIZE_ROLE);
            BitmapFont small = generate(UiTheme.SIZE_ROOM);
            BitmapFont body = generate(UiTheme.SIZE_BODY);
            BitmapFont bodyBig = generate(UiTheme.SIZE_CTA);
            BitmapFont huge = generate(UiTheme.SIZE_HUGE);
            Gdx.app.log("ui", "fonts: nunito sans semibold, 7 sizes");
            return new UiFonts(display, mid, small, body, bodyBig, bodyBig, bodyBig, huge);
        } catch (Throwable t) {
            Gdx.app.error("ui", "freetype unavailable, falling back to the built-in font", t);
            BitmapFont base = new BitmapFont();
            BitmapFont scaled = new BitmapFont();
            scaled.getData().setScale(2.6f);
            BitmapFont scaledSmall = new BitmapFont();
            scaledSmall.getData().setScale(1.9f);
            return new UiFonts(scaled, scaledSmall, base, base, base, base, base, scaled);
        }
    }

    private static BitmapFont generate(int size) {
        com.badlogic.gdx.files.FileHandle file = Gdx.files.internal(PATH);
        if (!file.exists()) throw new IllegalStateException("missing font " + PATH);
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(file);
        try {
            FreeTypeFontParameter p = new FreeTypeFontParameter();
            p.size = size;
            p.color = Color.WHITE;
            p.minFilter = Texture.TextureFilter.Linear;
            p.magFilter = Texture.TextureFilter.Linear;
            p.kerning = true;
            return generator.generateFont(p);
        } finally {
            generator.dispose();
        }
    }

    void dispose() {
        for (BitmapFont f : all) {
            if (f != null) f.dispose();
        }
    }
}