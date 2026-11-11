package com.topshelf.launcher;

/**
 * The one set of steps every screen draws from, so they read as one app: how strong a white or a black is,
 * how big small text is, and how long things take to move. (Each screen used to pick its own numbers: 350
 * different colours, 40 text sizes and 35 timings, most of them a hair apart.)
 *
 * Use the nearest step rather than a new number. tools/tokens.py swaps a file's literal numbers for these.
 * The look's own colours (the highlight, the corners) stay in GlassPanel; the sports palette in SportsLook.
 */
final class Ui {
    private Ui() { }

    // ------------------------------------------------------------------ white, by strength (text, rings, fills)
    /** text: bright, normal, soft, dim, faint */
    static final int WHITE_95 = 0xF2FFFFFF, WHITE_85 = 0xD9FFFFFF, WHITE_75 = 0xBFFFFFFF, WHITE_60 = 0x99FFFFFF, WHITE_50 = 0x80FFFFFF;
    /** fills and edges on a dark ground: strongest to barely there */
    static final int WHITE_40 = 0x66FFFFFF, WHITE_25 = 0x40FFFFFF, WHITE_20 = 0x33FFFFFF, WHITE_12 = 0x1FFFFFFF, WHITE_7 = 0x12FFFFFF;

    // ------------------------------------------------------------------ black, by strength (shades, text shadows)
    static final int BLACK_90 = 0xE6000000, BLACK_85 = 0xD9000000, BLACK_70 = 0xB3000000, BLACK_60 = 0x99000000,
            BLACK_55 = 0x8C000000, BLACK_45 = 0x73000000, BLACK_25 = 0x40000000;

    // ------------------------------------------------------------------ small text (rem); headlines keep their own sizes
    static final float TEXT_XS = .9f, TEXT_S = 1.05f, TEXT_M = 1.2f, TEXT_L = 1.35f, TEXT_XL = 1.5f;

    // ------------------------------------------------------------------ how long things take (ms)
    /** a press, a highlight · a small change · a panel or a menu · a row sliding · a screen changing · a slow fade */
    static final int QUICK = 150, FAST = 200, BASE = 250, MOVE = 300, SLIDE = 400, SLOW = 500, SLOWER = 600;
}
