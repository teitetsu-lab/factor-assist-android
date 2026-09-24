package jp.factor.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

/** Text readings as the phone's recogniser actually returned them (from the saved crops), matched to names. */
public class NameMatchTest {
    static final Map<String,String> NAMES=new HashMap<>();
    static{
        for(String name:new String[]{"安田記念","菊花賞","中距離","秋ウマ娘○","秋ウマ娘◎","光れ！弾けろ！超新星爆発！","JBCクラシック","賢さ","マイル","探求心","存在感","桜花賞"})
            NAMES.put(Store.normalize(name),name);
    }
    static String m(String raw){return NameMatch.match(raw,NAMES);}

    @Test public void exactReadingsMatch(){assertEquals("安田記念",m("安田記念"));assertEquals("存在感",m("存在感"));}
    @Test public void aStrayFirstCharacterIsDropped(){
        assertEquals("安田記念",m(")安田記念"));assertEquals("菊花賞",m("ト菊花賞"));assertEquals("菊花賞",m("、菊花賞"));assertEquals("探求心",m("ト探求心"));
    }
    @Test public void theBadgeReadAlongIsDropped(){assertEquals("中距離",m("中距離 指定"));assertEquals("中距離",m(")中距離 指定"));assertEquals("マイル",m("マイル指定"));}
    @Test public void aLatinOAtTheEndIsTheCircle(){assertEquals("秋ウマ娘○",m("秋ウマ娘O"));assertEquals("秋ウマ娘○",m("」秋ウマ娘○"));}
    @Test public void oneWrongCharacterIsForgiven(){
        assertEquals("光れ！弾けろ！超新星爆発！",m("光れ!弾けろ!超新量爆発!"));
        assertEquals("JBCクラシック",m("こBCクラシック"));
    }
    @Test public void twoNamesEquallyCloseAreNotGuessedBetween(){
        // 秋ウマ娘△ is one character from both ○ and ◎.
        assertNull(m("秋ウマ娘△"));
    }
    @Test public void nothingOrShortNoiseMatchesNothing(){assertNull(m(""));assertNull(m("ゾ"));assertNull(m("まったく別の文字列です"));}
    @Test public void editDistanceOfOne(){
        assertTrue(NameMatch.withinOne("abc","abd"));assertTrue(NameMatch.withinOne("abc","ab"));assertTrue(NameMatch.withinOne("abc","abxc"));
        assertFalse(NameMatch.withinOne("abc","axd"));assertFalse(NameMatch.withinOne("abc","a"));
    }
}
