package jp.factor.overlay;

import android.content.Context;

/**
 * Where the app draws on the game, set on the 読み取り位置の調整 screen. Across the screen every place is a share of
 * the screen width: the game's layout does not move sideways, so a place worked out afresh from each frame (a
 * card's height, the row spacing) only made what is drawn shake by a pixel or two. Down the screen places follow
 * the rows, which scroll.
 *
 * letterX/letterSize: the level letter on a factor row (its centre), and its height, as shares of the width.
 * lineY/lineStart/lineEnd/badge: the line drawn over the grey line under a skill's name - its height below the
 * card's top edge in row halves (FactorFrames.Snapshot.rowHalf), its ends as shares of the width, and the height
 * of the labels on it in row halves.
 * nameX: where the text written under a skill's name starts, as a share of the width.
 * frameX: the frames' left edge, as a share of the width; the right edge mirrors it, as the lists are centred.
 */
final class DrawPlaces {
    final double letterX,letterSize,lineY,lineStart,lineEnd,badge,nameX,frameX;
    DrawPlaces(double letterX,double letterSize,double lineY,double lineStart,double lineEnd,double badge,double nameX,double frameX){
        this.letterX=letterX;this.letterSize=letterSize;this.lineY=lineY;this.lineStart=lineStart;this.lineEnd=lineEnd;this.badge=badge;this.nameX=nameX;this.frameX=frameX;
    }
    /** Measured on the S25 (1080 wide): the letter just left of the stars, the line over the grey one, the name's own left edge, the cards' edge. */
    static final DrawPlaces DEFAULT=new DrawPlaces(773/1080.0,.055,1.056,.224,.68,.48,212/1080.0,48/1080.0);
    static volatile DrawPlaces current=DEFAULT;

    DrawPlaces withLetter(double x,double size){return new DrawPlaces(x,size,lineY,lineStart,lineEnd,badge,nameX,frameX);}
    DrawPlaces withLine(double y,double start,double end,double height){return new DrawPlaces(letterX,letterSize,y,start,end,height,nameX,frameX);}
    DrawPlaces withName(double x){return new DrawPlaces(letterX,letterSize,lineY,lineStart,lineEnd,badge,x,frameX);}
    DrawPlaces withFrame(double x){return new DrawPlaces(letterX,letterSize,lineY,lineStart,lineEnd,badge,nameX,x);}

    // The letter was once kept in card heights from the list's edge (drawLetterUnits); that setting is not carried
    // over, as a share of the width cannot be made from it without a screenshot.
    static DrawPlaces load(Context c){
        var p=Store.prefs(c);D d=new D(p);
        return new DrawPlaces(d.get("drawLetterX",DEFAULT.letterX),d.get("drawLetterSize",DEFAULT.letterSize),d.get("drawLineY",DEFAULT.lineY),
            d.get("drawLineStart",DEFAULT.lineStart),d.get("drawLineEnd",DEFAULT.lineEnd),d.get("drawBadge",DEFAULT.badge),d.get("drawNameX",DEFAULT.nameX),d.get("drawFrameX",DEFAULT.frameX));
    }
    private record D(android.content.SharedPreferences p){double get(String key,double fallback){return p.getFloat(key,(float)fallback);}}
    void save(Context c){
        Store.prefs(c).edit().remove("drawLetterUnits").putFloat("drawLetterX",(float)letterX).putFloat("drawLetterSize",(float)letterSize)
            .putFloat("drawLineY",(float)lineY).putFloat("drawLineStart",(float)lineStart).putFloat("drawLineEnd",(float)lineEnd)
            .putFloat("drawBadge",(float)badge).putFloat("drawNameX",(float)nameX).putFloat("drawFrameX",(float)frameX).apply();
        current=this;
    }
}
