package jp.factor.overlay;

import android.content.Context;

/**
 * Where the app draws on the game when the whole screen is shared (see FactorFrames), set on the 読み取り位置の調整
 * screen. Everything is measured from what is the same for every row of a page - the list's left edge and usual
 * card height, the row height, the screen width - so it stays put from row to row.
 *
 * letterUnits/letterSize: the level letter on a factor row, across the card in usual card heights from the list's
 * left edge, and its height as a share of the screen width.
 * lineY/lineStart/lineEnd/badge: the line drawn over the grey line under a skill's name - its height below the
 * card's top edge in row halves (FactorFrames.Snapshot.rowHalf), its ends as shares of the screen width, and the
 * height of the labels on it in row halves.
 */
final class DrawPlaces {
    final double letterUnits,letterSize,lineY,lineStart,lineEnd,badge;
    DrawPlaces(double letterUnits,double letterSize,double lineY,double lineStart,double lineEnd,double badge){
        this.letterUnits=letterUnits;this.letterSize=letterSize;this.lineY=lineY;this.lineStart=lineStart;this.lineEnd=lineEnd;this.badge=badge;
    }
    /** Measured on the S25: the letter just left of the stars, the line over the grey one, short of the - button. */
    static final DrawPlaces DEFAULT=new DrawPlaces(7.55,.055,1.056,.224,.68,.48);
    static volatile DrawPlaces current=DEFAULT;

    static DrawPlaces load(Context c){
        var p=Store.prefs(c);if(!p.contains("drawLetterUnits"))return DEFAULT;
        return new DrawPlaces(p.getFloat("drawLetterUnits",(float)DEFAULT.letterUnits),p.getFloat("drawLetterSize",(float)DEFAULT.letterSize),
            p.getFloat("drawLineY",(float)DEFAULT.lineY),p.getFloat("drawLineStart",(float)DEFAULT.lineStart),
            p.getFloat("drawLineEnd",(float)DEFAULT.lineEnd),p.getFloat("drawBadge",(float)DEFAULT.badge));
    }
    void save(Context c){
        Store.prefs(c).edit().putFloat("drawLetterUnits",(float)letterUnits).putFloat("drawLetterSize",(float)letterSize)
            .putFloat("drawLineY",(float)lineY).putFloat("drawLineStart",(float)lineStart).putFloat("drawLineEnd",(float)lineEnd)
            .putFloat("drawBadge",(float)badge).apply();
        current=this;
    }
    static void reset(Context c){
        Store.prefs(c).edit().remove("drawLetterUnits").remove("drawLetterSize").remove("drawLineY").remove("drawLineStart")
            .remove("drawLineEnd").remove("drawBadge").apply();
        current=DEFAULT;
    }
}
